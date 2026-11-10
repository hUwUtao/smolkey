use super::{FloatParameter, Parameters, Processor};

const AMOUNT: usize = 0;

// Keep a strong wet return at full send, including on a mono speaker.
const WET_AT_FULL_SEND: f32 = 0.60;

/// Four damped delay lines with an orthogonal Householder feedback mix.
pub(super) struct Room {
    delays: [Delay; 4],
    damping: f32,
    step: f32,
    wet: f32,
}

struct Delay {
    samples: Box<[f32]>,
    index: usize,
    filtered: f32,
}

impl Room {
    pub(super) const PARAMETERS: &'static [FloatParameter] = &[FloatParameter {
        default: 0.0,
        min: 0.0,
        max: 1.0,
    }];

    pub(super) fn new(rate: u32) -> Self {
        let rate = rate as f32;
        Self {
            delays: [19.9, 29.7, 37.1, 43.7].map(|ms| Delay {
                samples: vec![0.0; (rate * ms / 1000.0).round().max(1.0) as usize].into(),
                index: 0,
                filtered: 0.0,
            }),
            damping: 1.0 - (-std::f32::consts::TAU * 2700.0 / rate).exp(),
            step: 1.0 / (rate * 0.020),
            wet: 0.0,
        }
    }
}

impl Processor for Room {
    fn clear(&mut self) {
        for delay in &mut self.delays {
            delay.samples.fill(0.0);
            delay.index = 0;
            delay.filtered = 0.0;
        }
        self.wet = 0.0;
    }

    fn process(&mut self, frames: &mut [[f32; 2]], parameters: &Parameters) {
        let target = parameters.float(AMOUNT) * WET_AT_FULL_SEND;
        if target == 0.0 && self.wet == 0.0 {
            return;
        }
        for [left, right] in frames {
            self.wet += (target - self.wet).clamp(-self.step, self.step);
            let [a, b, c, d] = self.delays.each_mut().map(|delay| {
                delay.filtered += self.damping * (delay.samples[delay.index] - delay.filtered);
                // Flush inaudible feedback to zero before it reaches denormal range.
                delay.filtered = (delay.filtered + 1.0e-20) - 1.0e-20;
                delay.filtered
            });
            let reflection = (a + b + c + d) * 0.5;
            for (delay, (tap, input)) in
                self.delays
                    .iter_mut()
                    .zip([(a, *left), (b, *right), (c, -*left), (d, -*right)])
            {
                delay.samples[delay.index] = input * 0.5 + (tap - reflection) * 0.86;
                delay.index += 1;
                if delay.index == delay.samples.len() {
                    delay.index = 0;
                }
            }
            *left += self.wet * (a - c + (b + d) * 0.5);
            *right += self.wet * (b - d + (a + c) * 0.5);
        }
        if target == 0.0 && self.wet == 0.0 {
            self.clear();
        }
    }
}
