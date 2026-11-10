use std::sync::atomic::{AtomicU32, Ordering::Relaxed};
use std::sync::{Arc, OnceLock};

mod limiter;
mod low_pass;
pub(crate) use low_pass::LowPass;
mod mono_fold;
mod reverb;

use limiter::Limiter;
use mono_fold::MonoFold;
use reverb::Room;

const MAX_EFFECTS: usize = 16;

/// A stateful stage that processes a block of stereo frames in place.
trait Processor: Send {
    fn process(&mut self, frames: &mut [[f32; 2]], parameters: &Parameters);
    fn clear(&mut self);
}

struct FloatParameter {
    default: f32,
    min: f32,
    max: f32,
}

#[derive(Clone, Copy)]
pub(crate) struct EffectKind {
    schema: &'static [FloatParameter],
    prepare: fn(u32, usize) -> Box<dyn Processor>,
}

impl EffectKind {
    pub(crate) const REVERB: Self = Self {
        schema: Room::PARAMETERS,
        prepare: |rate, _| Box::new(Room::new(rate)),
    };
    pub(crate) const MONO_FOLD: Self = Self {
        schema: &[],
        prepare: |_, channels| Box::new(MonoFold(channels == 1)),
    };
    pub(crate) const LIMITER: Self = Self {
        schema: Limiter::PARAMETERS,
        prepare: |rate, _| Box::new(Limiter::new(rate)),
    };

    // IDs are one-based and stable across the JNI boundary.
    const BUILT_INS: [Self; 3] = [Self::REVERB, Self::MONO_FOLD, Self::LIMITER];

    pub(crate) fn from_id(id: i32) -> Option<Self> {
        let index = usize::try_from(id).ok()?.checked_sub(1)?;
        Self::BUILT_INS.get(index).copied()
    }
}

struct Parameters {
    schema: &'static [FloatParameter],
    values: Box<[AtomicU32]>,
}

impl Parameters {
    fn new(kind: EffectKind) -> Self {
        let schema = kind.schema;
        Self {
            schema,
            values: schema
                .iter()
                .map(|parameter| AtomicU32::new(parameter.default.to_bits()))
                .collect(),
        }
    }

    /// DSP uses its own fixed parameter IDs and reads values once per block.
    fn float(&self, parameter: usize) -> f32 {
        f32::from_bits(self.values[parameter].load(Relaxed))
    }

    fn set_float(&self, parameter: usize, value: f32) -> bool {
        let Some(spec) = self.schema.get(parameter) else {
            return false;
        };
        if !value.is_finite() || !(spec.min..=spec.max).contains(&value) {
            return false;
        }
        self.values[parameter].store(value.to_bits(), Relaxed);
        true
    }
}

/// Published slots are stable for the engine lifetime. Parameter writes bypass the mixer lock.
pub(crate) struct EffectControls([OnceLock<Arc<Parameters>>; MAX_EFFECTS]);

impl EffectControls {
    pub(crate) fn set_float(&self, slot: usize, parameter: usize, value: f32) -> bool {
        self.0
            .get(slot)
            .and_then(OnceLock::get)
            .is_some_and(|parameters| parameters.set_float(parameter, value))
    }
}

struct Stage {
    kind: EffectKind,
    parameters: Arc<Parameters>,
    processor: Option<Box<dyn Processor>>,
}

pub(crate) struct EffectChain {
    stages: Vec<Stage>,
    controls: Arc<EffectControls>,
    format: Option<(u32, usize)>,
}

impl EffectChain {
    pub(crate) fn new() -> Self {
        Self {
            stages: Vec::with_capacity(MAX_EFFECTS),
            controls: Arc::new(EffectControls(std::array::from_fn(|_| OnceLock::new()))),
            format: None,
        }
    }

    pub(crate) fn controls(&self) -> Arc<EffectControls> {
        Arc::clone(&self.controls)
    }

    pub(crate) fn load(&mut self, kind: EffectKind) -> Option<usize> {
        let slot = self.stages.len();
        let control = self.controls.0.get(slot)?;
        let parameters = Arc::new(Parameters::new(kind));
        let processor = self
            .format
            .map(|(rate, channels)| (kind.prepare)(rate, channels));
        control.set(Arc::clone(&parameters)).ok()?;
        self.stages.push(Stage {
            kind,
            parameters,
            processor,
        });
        Some(slot)
    }

    pub(crate) fn prepare(&mut self, rate: u32, channels: usize) {
        self.format = Some((rate, channels));
        for stage in &mut self.stages {
            stage.processor = Some((stage.kind.prepare)(rate, channels));
        }
    }

    pub(crate) fn process(&mut self, frames: &mut [[f32; 2]]) {
        for stage in &mut self.stages {
            if let Some(processor) = &mut stage.processor {
                processor.process(frames, &stage.parameters);
            }
        }
    }

    pub(crate) fn clear(&mut self) {
        for stage in &mut self.stages {
            if let Some(processor) = &mut stage.processor {
                processor.clear();
            }
        }
    }
}
