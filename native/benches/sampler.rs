//! Compile the sampler modules directly so the Android cdylib stays private.
#![allow(dead_code)]

#[path = "../src/bank.rs"]
mod bank;
#[path = "../src/fx/mod.rs"]
mod fx;
#[path = "../src/mixer.rs"]
mod mixer;
#[path = "../src/streaming.rs"]
mod streaming;

use std::fs::File;
use std::hint::black_box;
use std::sync::Arc;

use criterion::{
    BatchSize, BenchmarkGroup, BenchmarkId, Criterion, Throughput, criterion_group, criterion_main,
    measurement::WallTime,
};

use bank::{Bank, RATE, Sample, decode_stream};
use fx::{EffectChain, EffectKind};
use mixer::Mixer;

const BANK: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../app/src/main/assets/instruments.smb"
);
const FRAMES: usize = 128;
const CHANNELS: usize = 2;

fn effects(c: &mut Criterion) {
    let mut group = c.benchmark_group("effects_128_frames");
    group.throughput(Throughput::Elements(FRAMES as u64));
    bench_effect(&mut group, "reverb_35", EffectKind::REVERB, 0.35, 0.5);
    bench_effect(
        &mut group,
        "limiter_below_ceiling",
        EffectKind::LIMITER,
        80.0,
        0.5,
    );
    bench_effect(
        &mut group,
        "limiter_engaged",
        EffectKind::LIMITER,
        80.0,
        1.25,
    );
}

fn bench_effect(
    group: &mut BenchmarkGroup<'_, WallTime>,
    name: &str,
    kind: EffectKind,
    parameter: f32,
    peak: f32,
) {
    let mut effect = EffectChain::new();
    let slot = effect.load(kind).unwrap();
    assert!(effect.controls().set_float(slot, 0, parameter));
    effect.prepare(RATE, CHANNELS);
    let input: [[f32; 2]; FRAMES] = std::array::from_fn(|frame| {
        let phase = frame as f32 * std::f32::consts::TAU / FRAMES as f32;
        [peak * phase.sin(), peak * 0.8 * (phase * 3.0).cos()]
    });
    group.bench_function(name, |b| {
        // Each block gets fresh input, while the processor keeps its audio history.
        b.iter_batched_ref(
            || input,
            |frames| {
                effect.process(black_box(frames));
                black_box(&*frames);
            },
            BatchSize::NumIterations(16),
        );
    });
}

fn render(c: &mut Criterion) {
    let mut mixer = Mixer::new();
    let room = mixer.effects.load(EffectKind::REVERB).unwrap();
    mixer.effects.load(EffectKind::MONO_FOLD).unwrap();
    mixer.effects.load(EffectKind::LIMITER).unwrap();
    let effects = mixer.effects.controls();
    let input = Arc::new(File::open(BANK).expect("bundled bank file"));
    let length = input.metadata().unwrap().len();
    let index = Bank::read_file(Arc::clone(&input), 0, length).expect("bundled bank index");
    let zones = &index.programs[&0].zones;
    let stems: Vec<_> = (0..16)
        .map(|i| {
            let clip = index.clips[zones[i * zones.len() / 16].clip];
            let mut pcm = Vec::with_capacity(clip.frames * clip.channels);
            decode_stream(
                clip.stream(Arc::clone(&input)),
                clip.rate,
                clip.frames,
                |_, chunk| {
                    pcm.extend_from_slice(chunk);
                    Some(())
                },
            )
            .expect("bundled clip");
            mixer
                .load(Sample {
                    pcm,
                    channels: clip.channels,
                    rate: clip.rate,
                })
                .ok()
                .expect("benchmark clip fits")
        })
        .collect();

    let mut group = c.benchmark_group("render_128_frames");
    group.throughput(Throughput::Elements(FRAMES as u64));
    for (voices, reverb) in [(0, 0), (16, 0), (16, 35), (128, 0)] {
        mixer.clear();
        assert!(effects.set_float(room, 0, reverb as f32 / 100.0));
        mixer.prepare_output(RATE, CHANNELS);

        // Spread voices across the bank and loop them to keep polyphony fixed.
        for voice in 0..voices {
            let stem = stems[voice * stems.len() / voices];
            let speed = [0.943874, 1.0, 1.059463][voice % 3];
            assert_ne!(mixer.fire(stem, 0.08, speed, true), 0);
        }

        let mut output = [0.0f32; FRAMES * CHANNELS];
        group.bench_function(BenchmarkId::new(format!("reverb_{reverb}"), voices), |b| {
            b.iter(|| {
                mixer.render(black_box(&mut output), CHANNELS, RATE);
                black_box(&output);
            });
        });
    }
}

criterion_group!(benches, effects, render);
criterion_main!(benches);
