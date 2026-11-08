/// A stateful stage that processes a block of stereo frames in place.
pub(crate) trait Processor {
    fn process(&mut self, frames: &mut [[f32; 2]]);

    /// Discard audio history while retaining the current settings.
    fn clear(&mut self);
}

pub(crate) struct Chain<First, Next> {
    first: First,
    next: Next,
}

impl<First: Processor, Next: Processor> Chain<First, Next> {
    pub(crate) fn new(first: First, next: Next) -> Self {
        Self { first, next }
    }

    pub(crate) fn first_mut(&mut self) -> &mut First {
        &mut self.first
    }
}

impl<First: Processor, Next: Processor> Processor for Chain<First, Next> {
    fn process(&mut self, frames: &mut [[f32; 2]]) {
        self.first.process(frames);
        self.next.process(frames);
    }

    fn clear(&mut self) {
        self.first.clear();
        self.next.clear();
    }
}

/// Fold mono output before peak detection while keeping stereo block storage.
pub(crate) struct MonoFold(pub(crate) bool);

impl Processor for MonoFold {
    fn process(&mut self, frames: &mut [[f32; 2]]) {
        if self.0 {
            for frame in frames {
                *frame = [(frame[0] + frame[1]) * 0.5; 2];
            }
        }
    }

    fn clear(&mut self) {}
}
