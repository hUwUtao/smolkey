//! Compile the sampler modules directly so the Android cdylib stays private.
#![allow(dead_code)]

#[path = "../src/bank.rs"]
mod bank;
#[path = "../src/limiter.rs"]
mod limiter;
#[path = "../src/mixer.rs"]
mod mixer;
#[path = "../src/processor.rs"]
mod processor;
#[path = "../src/reverb.rs"]
mod reverb;

use std::hint::black_box;

use criterion::{
    BatchSize, BenchmarkGroup, BenchmarkId, Criterion, Throughput, criterion_group, criterion_main,
    measurement::WallTime,
};

use bank::{RATE, decode_bank};
use limiter::Limiter;
use mixer::Mixer;
use processor::Processor;
use reverb::Room;

const BANK: &[u8] = include_bytes!("../../app/src/main/assets/instruments.smb");
const FRAMES: usize = 128;
const CHANNELS: usize = 2;

fn effects(c: &mut Criterion) {
    let mut group = c.benchmark_group("effects_128_frames");
    group.throughput(Throughput::Elements(FRAMES as u64));
    bench_effect(&mut group, "reverb_35", Room::new(RATE, 0.35), 0.5);
    bench_effect(&mut group, "limiter_below_ceiling", Limiter::new(RATE), 0.5);
    bench_effect(&mut group, "limiter_engaged", Limiter::new(RATE), 1.25);
}

fn bench_effect(
    group: &mut BenchmarkGroup<'_, WallTime>,
    name: &str,
    mut effect: impl Processor,
    peak: f32,
) {
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
    let mut stems = Vec::new();
    decode_bank(BANK, |clip| {
        if clip.zones.iter().any(|zone| zone.low < 128) {
            stems.push(mixer.load(clip.sample).ok().expect("piano stem fits"));
        }
    })
    .expect("bundled instrument bank");
    assert!(!stems.is_empty(), "bundled bank has no piano recordings");

    let mut group = c.benchmark_group("render_128_frames");
    group.throughput(Throughput::Elements(FRAMES as u64));
    for (voices, reverb) in [(0, 0), (16, 0), (16, 35), (128, 0)] {
        mixer.clear();
        mixer.set_reverb(reverb);
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
