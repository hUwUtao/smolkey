use dasp_sample::{FromSample, Sample as OutputSample};

use crate::bank::{DRUMS, PCM_BUDGET, Playback, Sample, Zone};
use crate::limiter::Limiter;
use crate::processor::{Chain, MonoFold, Processor};
use crate::reverb::Room;

const POLYPHONY: usize = 128;
const STEAL_TAILS: usize = 16;
const MAX_STEMS: usize = 512;
type OutputChain = Chain<Room, Chain<MonoFold, Limiter>>;

struct Voice {
    id: i64,
    stem: usize,
    note: Option<usize>,
    position: f64,
    speed: f64,
    gain: f32,
    target: f32,
    envelope: f32,
    attack: f32,
    release: f32,
    release_level: f32,
    key_up: bool,
    peak: f32,
    damper: f32,
    cutoff: f32,
    filtered: [f32; 2],
    looping: bool,
}

#[derive(Clone, Copy)]
struct PianoSample {
    stem: usize,
    speed: f64,
    gain: f32,
    damper: f32,
}

pub struct Mixer {
    stems: Vec<Sample>,
    pcm_bytes: usize,
    voices: [Option<Voice>; POLYPHONY],
    steal_tails: [Option<Voice>; STEAL_TAILS],
    next_voice: i64,
    piano: [[Option<PianoSample>; 128]; 4],
    pending: [u8; 16 * 128],
    bank_ready: bool,
    playback: Option<Playback>,
    processors: Option<OutputChain>,
    reverb: f32,
}

impl Mixer {
    pub fn new() -> Self {
        Self {
            stems: Vec::with_capacity(MAX_STEMS),
            pcm_bytes: 0,
            voices: std::array::from_fn(|_| None),
            steal_tails: std::array::from_fn(|_| None),
            next_voice: 1,
            piano: [[None; 128]; 4],
            pending: [0; 16 * 128],
            bank_ready: false,
            playback: None,
            processors: None,
            reverb: 0.0,
        }
    }

    pub fn prepare_output(&mut self, rate: u32, channels: usize) {
        self.processors = Some(Chain::new(
            Room::new(rate, self.reverb),
            Chain::new(MonoFold(channels == 1), Limiter::new(rate)),
        ));
    }

    pub fn set_reverb(&mut self, percent: i32) {
        self.reverb = percent.clamp(0, 100) as f32 / 100.0;
        if let Some(processors) = &mut self.processors {
            processors.first_mut().set_amount(self.reverb);
        }
    }

    pub fn load(&mut self, sample: Sample) -> Result<usize, Sample> {
        let bytes = sample.pcm.len() * size_of::<i16>();
        if self.pcm_bytes + bytes > PCM_BUDGET || self.stems.len() == MAX_STEMS {
            return Err(sample);
        }
        let index = self.stems.len();
        self.stems.push(sample);
        self.pcm_bytes += bytes;
        Ok(index)
    }

    pub fn install(&mut self, playback: Playback, zones: Vec<(Zone, usize)>) -> [i32; DRUMS] {
        self.playback = Some(playback);
        let mut drums = [None; DRUMS];
        let mut recorded = Vec::new();
        for (zone, stem) in zones {
            if zone.low >= 128 {
                drums[usize::from(zone.low - 128)].get_or_insert(stem);
                continue;
            }
            let keys = &mut self.piano[zone.layer as usize];
            for note in zone.low..=zone.high {
                keys[note as usize].get_or_insert(PianoSample::mapped(stem, note, zone));
            }
            recorded.push((zone, stem));
        }
        for (layer, keys) in self.piano.iter_mut().enumerate() {
            for (note, slot) in keys
                .iter_mut()
                .enumerate()
                .filter(|(_, slot)| slot.is_none())
            {
                // Keep playing if a recording is corrupt or a pad is outside the piano range.
                *slot = recorded
                    .iter()
                    .min_by_key(|(zone, _)| {
                        let layer_distance = (zone.layer as usize).abs_diff(layer);
                        (
                            (zone.root as usize).abs_diff(note) + 4 * layer_distance,
                            layer_distance,
                        )
                    })
                    .map(|&(zone, stem)| PianoSample::mapped(stem, note as u8, zone));
            }
        }
        self.bank_ready = self.piano[0][0].is_some();
        if self.bank_ready {
            for (note, velocity) in std::mem::replace(&mut self.pending, [0; 16 * 128])
                .into_iter()
                .enumerate()
                .filter(|(_, velocity)| *velocity != 0)
            {
                self.note(note, velocity);
            }
        }
        drums.map(|id| id.map_or(-1, |id| id as i32))
    }

    pub fn note(&mut self, key: usize, velocity: u8) {
        if !self.bank_ready {
            self.pending[key] = velocity;
            return;
        }
        let Some(playback) = &self.playback else {
            return;
        };
        if velocity == 0 {
            for voice in self
                .voices
                .iter_mut()
                .flatten()
                .filter(|v| v.note == Some(key) && !v.key_up && v.release == 0.0)
            {
                voice.key_up = true;
                voice.release = voice.damper;
            }
            return;
        }
        let layer = playback
            .piano_velocity_max
            .partition_point(|&max| velocity > max);
        let note = key % 128;
        let Some(sample) = self.piano[layer][note] else {
            return;
        };
        for voice in self
            .voices
            .iter_mut()
            .flatten()
            .filter(|v| v.note == Some(key))
        {
            // Fade the old vibration without extending a release already in progress.
            if voice.release == 0.0 || voice.release > playback.piano_restrike {
                voice.release = playback.piano_restrike;
            }
        }
        let gain = sample.gain
            * playback.piano_gain
            * (f32::from(velocity) / 127.0).powf(playback.piano_velocity_exponent);
        // Splendid uses a filtered PP recording for the lightest hammer strikes.
        let cutoff = if velocity <= playback.piano_soft_velocity {
            let [low, high] = playback.piano_soft_hz;
            low * (high / low)
                .powf(f32::from(velocity - 1) / f32::from(playback.piano_soft_velocity - 1))
        } else {
            0.0
        };
        self.spawn(Voice {
            note: Some(key),
            damper: sample.damper,
            cutoff,
            attack: playback.piano_attack,
            ..Voice::new(sample.stem, gain, sample.speed, false)
        });
    }

    pub fn percussion(&mut self, stem: usize, velocity: u8, looping: bool, current: i64) -> i64 {
        if velocity == 0 {
            self.stop(current);
            return 0;
        }
        let Some(playback) = &self.playback else {
            return 0;
        };
        let attack = playback.drum_attack;
        let gain = (f32::from(velocity) / 127.0).powf(playback.drum_velocity_exponent);
        if looping && self.gain(current, gain) {
            return current;
        }
        self.spawn(Voice {
            attack,
            ..Voice::new(stem, gain, 1.0, looping)
        })
    }

    pub fn fire(&mut self, stem: usize, gain: f32, speed: f64, looping: bool) -> i64 {
        self.spawn(Voice::new(stem, gain, speed, looping))
    }

    fn spawn(&mut self, mut voice: Voice) -> i64 {
        if voice.stem >= self.stems.len() {
            return 0;
        }
        let id = self.next_voice;
        self.next_voice = id.checked_add(1).unwrap_or(1);
        let slot = self
            .voices
            .iter_mut()
            .min_by_key(|slot| {
                slot.as_ref().map(|v| {
                    let held = v.release == 0.0 && (v.note.is_some() && !v.key_up || v.looping);
                    (held, v.peak.to_bits(), v.id)
                })
            })
            .unwrap();
        voice.id = id;
        if let Some(mut stolen) = slot.replace(voice) {
            // Give an evicted voice a few milliseconds to reach zero.
            stolen.release = 0.005;
            let tail = self
                .steal_tails
                .iter_mut()
                .min_by_key(|slot| slot.as_ref().map(|v| (v.peak.to_bits(), v.id)))
                .unwrap();
            *tail = Some(stolen);
        }
        id
    }

    pub fn stop(&mut self, id: i64) {
        if let Some(voice) = self.voices.iter_mut().flatten().find(|v| v.id == id) {
            voice.release = 0.02;
        }
    }

    pub fn gain(&mut self, id: i64, gain: f32) -> bool {
        let Some(voice) = self
            .voices
            .iter_mut()
            .flatten()
            .find(|v| v.id == id && v.release == 0.0)
        else {
            return false;
        };
        voice.target = gain;
        true
    }

    pub fn clear(&mut self) {
        self.pending.fill(0);
        self.voices.fill_with(|| None);
        self.steal_tails.fill_with(|| None);
        if let Some(processors) = &mut self.processors {
            processors.clear();
        }
    }

    pub fn release(&mut self) {
        self.pending.fill(0);
        for voice in self.voices.iter_mut().flatten() {
            voice.release = 0.02;
        }
    }

    pub fn render<T: OutputSample + FromSample<f32>>(
        &mut self,
        output: &mut [T],
        channels: usize,
        rate: u32,
    ) {
        let Some(processors) = &mut self.processors else {
            output.fill(T::EQUILIBRIUM);
            return;
        };
        let rate = rate as f32;
        for chunk in output.chunks_mut(128 * channels) {
            let mut mix = [[0.0f32; 2]; 128];
            let frames = chunk.len() / channels;
            for slot in self.voices.iter_mut().chain(&mut self.steal_tails) {
                if let Some(voice) = slot {
                    let sample = &self.stems[voice.stem];
                    if !voice.mix(sample, &mut mix[..frames], rate) {
                        *slot = None;
                    }
                }
            }
            processors.process(&mut mix[..frames]);
            for (frame, [left, right]) in chunk.chunks_exact_mut(channels).zip(mix) {
                frame[0] = T::from_sample(left);
                if channels > 1 {
                    frame[1] = T::from_sample(right);
                    frame[2..].fill(T::from_sample(0.0));
                }
            }
        }
    }
}

impl PianoSample {
    fn mapped(stem: usize, note: u8, zone: crate::bank::Zone) -> Self {
        Self {
            stem,
            speed: 2f64.powf((f64::from(note) - f64::from(zone.root)) / 12.0),
            gain: 10f32.powf(f32::from(zone.gain_centidb) / 2000.0),
            damper: f32::from(zone.release_ms) / 1000.0,
        }
    }
}

impl Voice {
    fn new(stem: usize, gain: f32, speed: f64, looping: bool) -> Self {
        Self {
            id: 0,
            stem,
            note: None,
            position: 0.0,
            speed,
            gain,
            target: gain,
            envelope: 0.0,
            attack: 0.003,
            release: 0.0,
            release_level: 1.0,
            key_up: false,
            peak: gain,
            damper: 0.0,
            cutoff: 0.0,
            filtered: [0.0; 2],
            looping,
        }
    }

    fn mix(&mut self, sample: &Sample, output: &mut [[f32; 2]], rate: f32) -> bool {
        let frames = sample.frames();
        let step = self.speed * f64::from(sample.rate) / f64::from(rate);
        let attack_step = 1.0 / (rate * self.attack);
        let release_step = if self.release > 0.0 {
            1.0 / (rate * self.release)
        } else {
            0.0
        };
        let smoothing = 1.0 / (rate * 0.01);
        let filter = if self.cutoff > 0.0 {
            1.0 - (-std::f32::consts::TAU * self.cutoff / rate).exp()
        } else {
            0.0
        };
        let mut peak: f32 = 0.0;
        for [left, right] in output {
            if self.position >= frames as f64 {
                if !self.looping {
                    return false;
                }
                self.position %= frames as f64;
            }
            self.envelope = (self.envelope + attack_step).min(1.0);
            self.release_level -= release_step;
            if self.release_level <= 0.0 {
                return false;
            }
            let tail = if self.note.is_some() && release_step > 0.0 {
                self.release_level.powi(3)
            } else {
                self.release_level
            };
            self.gain += (self.target - self.gain).clamp(-smoothing, smoothing);
            let index = self.position as usize;
            let next = if index + 1 < frames {
                index + 1
            } else if self.looping {
                0
            } else {
                index
            };
            let fraction = (self.position - index as f64) as f32;
            let amplitude = self.gain * self.envelope * tail / 32768.0;
            let read = |channel| {
                let a = f32::from(sample.pcm[index * sample.channels + channel]);
                let b = f32::from(sample.pcm[next * sample.channels + channel]);
                a + fraction * (b - a)
            };
            let mut frame = [read(0), read(sample.channels - 1)];
            if self.cutoff > 0.0 {
                for (value, previous) in frame.iter_mut().zip(&mut self.filtered) {
                    *previous += filter * (*value - *previous);
                    *value = *previous;
                }
            }
            let voice_left = frame[0] * amplitude;
            let voice_right = frame[1] * amplitude;
            peak = peak.max(voice_left.abs()).max(voice_right.abs());
            *left += voice_left;
            *right += voice_right;
            self.position += step;
        }
        self.peak = peak;
        true
    }
}
