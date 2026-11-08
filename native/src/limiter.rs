use crate::processor::Processor;

const BLOCK_FRAMES: usize = 64;
const CEILING: f32 = 0.90;
const RELEASE_SECONDS: f32 = 0.080;

/// Stereo-linked peak limiter with two blocks of delay (2.7 ms at 48 kHz).
pub struct Limiter {
    blocks: [[[f32; 2]; BLOCK_FRAMES]; 2],
    block: usize,
    index: usize,
    peak: f32,
    pending_limit: f32,
    gain: f32,
    end_gain: f32,
    gain_step: f32,
    release_per_block: f32,
}

impl Limiter {
    pub fn new(rate: u32) -> Self {
        Self {
            blocks: [[[0.0; 2]; BLOCK_FRAMES]; 2],
            block: 0,
            index: 0,
            peak: 0.0,
            pending_limit: 1.0,
            gain: 1.0,
            end_gain: 1.0,
            gain_step: 0.0,
            release_per_block: BLOCK_FRAMES as f32 / (rate as f32 * RELEASE_SECONDS),
        }
    }

    fn process_frame(&mut self, input: [f32; 2]) -> [f32; 2] {
        let [left, right] = std::mem::replace(&mut self.blocks[self.block][self.index], input);
        let output = [left * self.gain, right * self.gain];
        self.peak = self.peak.max(input[0].abs()).max(input[1].abs());
        self.index += 1;
        self.gain += self.gain_step;
        if self.index == BLOCK_FRAMES {
            let input_limit = CEILING / self.peak.max(CEILING);
            self.gain = self.end_gain;
            // Both endpoints of the next block's ramp respect that block's peak.
            // Its end also prepares the gain for the block immediately after it.
            self.end_gain = self
                .pending_limit
                .min(input_limit)
                .min(self.gain + self.release_per_block);
            self.gain_step = (self.end_gain - self.gain) / BLOCK_FRAMES as f32;
            self.pending_limit = input_limit;
            self.peak = 0.0;
            self.index = 0;
            self.block ^= 1;
        }
        output
    }
}

impl Processor for Limiter {
    fn process(&mut self, frames: &mut [[f32; 2]]) {
        for frame in frames {
            *frame = self.process_frame(*frame);
        }
    }

    fn clear(&mut self) {
        self.blocks.fill([[0.0; 2]; BLOCK_FRAMES]);
        self.block = 0;
        self.index = 0;
        self.peak = 0.0;
        self.pending_limit = 1.0;
        self.gain = 1.0;
        self.end_gain = 1.0;
        self.gain_step = 0.0;
    }
}
