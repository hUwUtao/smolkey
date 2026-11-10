use super::{Parameters, Processor};

/// Fold mono output before subsequent effects while keeping stereo block storage.
pub(super) struct MonoFold(pub(super) bool);

impl Processor for MonoFold {
    fn process(&mut self, frames: &mut [[f32; 2]], _: &Parameters) {
        if self.0 {
            for frame in frames {
                *frame = [(frame[0] + frame[1]) * 0.5; 2];
            }
        }
    }

    fn clear(&mut self) {}
}
