/// Two-pole low-pass used by sample regions.
pub(crate) struct LowPass {
    pub(crate) hz: f32,
    rate: f32,
    coefficients: [f32; 5],
    state: [[f32; 2]; 2],
}

impl LowPass {
    pub(crate) fn new(hz: f32) -> Self {
        Self {
            hz,
            rate: 0.0,
            coefficients: [0.0; 5],
            state: [[0.0; 2]; 2],
        }
    }

    pub(crate) fn prepare(&mut self, rate: f32) {
        if self.rate == rate {
            return;
        }
        self.rate = rate;
        let radians = std::f32::consts::TAU * self.hz.min(rate * 0.49) / rate;
        let (sin, cos) = radians.sin_cos();
        let alpha = sin * std::f32::consts::FRAC_1_SQRT_2;
        let inverse = 1.0 / (1.0 + alpha);
        let b1 = (1.0 - cos) * inverse;
        self.coefficients = [
            b1 * 0.5,
            b1,
            b1 * 0.5,
            -2.0 * cos * inverse,
            (1.0 - alpha) * inverse,
        ];
        self.state = [[0.0; 2]; 2];
    }

    pub(crate) fn process(&mut self, frame: &mut [f32; 2]) {
        let [b0, b1, b2, a1, a2] = self.coefficients;
        for (sample, [previous, next]) in frame.iter_mut().zip(&mut self.state) {
            let output = *sample * b0 + *previous;
            *previous = *sample * b1 - a1 * output + *next;
            *next = *sample * b2 - a2 * output;
            *sample = output;
        }
    }
}
