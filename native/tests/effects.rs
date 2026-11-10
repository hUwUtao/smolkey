#[path = "../src/fx/mod.rs"]
mod fx;

use fx::{EffectChain, EffectKind, LowPass};

#[test]
fn reverb_parameters_are_validated_per_slot_and_survive_output_changes() {
    let mut rack = EffectChain::new();
    rack.prepare(48_000, 2);
    let mut dry = [[0.25, -0.125]];
    rack.process(&mut dry);
    assert_eq!(dry, [[0.25, -0.125]]);
    let controls = rack.controls();
    let room = rack.load(EffectKind::from_id(1).unwrap()).unwrap();
    let other = rack.load(EffectKind::REVERB).unwrap();
    let fold = rack.load(EffectKind::MONO_FOLD).unwrap();

    assert!(controls.set_float(room, 0, 0.8));
    assert!(!controls.set_float(room, 0, f32::NAN));
    assert!(!controls.set_float(room, 0, 2.0));
    assert!(!controls.set_float(room, 1, 0.0));
    assert!(!controls.set_float(fold, 0, 0.0));
    assert!(!controls.set_float(usize::MAX, 0, 0.0));

    for (rate, channels) in [(48_000, 2), (44_100, 1)] {
        rack.prepare(rate, channels);
        let mut output = vec![[0.0; 2]; 2400];
        output[0] = [1.0, 0.0];
        rack.process(&mut output);
        assert!(output[1..].iter().any(|frame| frame[0].abs() > 0.001));
        if channels == 1 {
            assert!(output.iter().all(|frame| frame[0] == frame[1]));
        }
    }

    assert!(controls.set_float(room, 0, 0.0));
    rack.clear();
    let mut dry = vec![[0.0; 2]; 2400];
    dry[0] = [1.0, 0.0];
    rack.process(&mut dry);
    assert_eq!(dry[0], [0.5; 2]);
    assert!(dry[1..].iter().all(|frame| *frame == [0.0; 2]));
    assert!(controls.set_float(other, 0, 0.5));
    rack.clear();
    rack.process(&mut dry);
    assert!(dry[1..].iter().any(|frame| frame[0].abs() > 0.001));
}

#[test]
fn limiter_release_changes_gain_recovery_without_changing_the_ceiling() {
    let render = |release_ms| {
        let mut rack = EffectChain::new();
        let limiter = rack.load(EffectKind::LIMITER).unwrap();
        rack.prepare(48_000, 2);
        assert!(rack.controls().set_float(limiter, 0, release_ms));
        let mut loud = [[2.0, 1.5]; 256];
        rack.process(&mut loud);
        assert!(loud.iter().flatten().all(|sample| sample.abs() <= 0.9001));
        let mut quiet = [[0.2; 2]; 960];
        rack.process(&mut quiet);
        quiet.last().unwrap()[0]
    };
    assert!(render(5.0) > render(2000.0) + 0.05);
}

#[test]
fn region_low_pass_attenuates_above_its_cutoff() {
    let amplitude = |hz: f32| {
        let mut filter = LowPass::new(1000.0);
        filter.prepare(48_000.0);
        let mut energy = 0.0;
        for i in 0..4800 {
            let sample = (std::f32::consts::TAU * hz * i as f32 / 48_000.0).sin();
            let mut frame = [sample, 0.0];
            filter.process(&mut frame);
            assert_eq!(frame[1], 0.0);
            if i >= 480 {
                energy += frame[0] * frame[0];
            }
        }
        (energy / 4320.0).sqrt()
    };
    assert!(amplitude(200.0) > 0.70);
    assert!(amplitude(4000.0) < 0.05);
}
