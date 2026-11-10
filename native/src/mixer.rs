use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicI16, Ordering::Relaxed};

use dasp_sample::{FromSample, Sample as OutputSample};
use smol::channel::Sender;

use crate::bank::{Bank, Program, Sample, StreamPcm, Zone};
use crate::fx::{EffectChain, LowPass};

const POLYPHONY: usize = 128;
const STEAL_TAILS: usize = 16;
const MAX_STANDALONE_CLIPS: usize = 512;
const STANDALONE_PCM_BUDGET: usize = 128 * 1024 * 1024;
const CACHE_PCM_BUDGET: usize = 32 * 1024 * 1024;
const MAX_CACHE_ENTRIES: usize = 512;

#[derive(Clone, Copy, PartialEq, Eq)]
enum ClipId {
    Bank(usize),
    Standalone(usize),
}

struct CachedClip {
    age: u64,
    state: ClipState,
}

enum ClipState {
    Queued,
    Reserved,
    Decoding(Arc<StreamPcm>),
    Ready(Arc<StreamPcm>),
}

struct StreamingBank {
    index: Bank,
    queue: Sender<usize>,
    cache: HashMap<usize, CachedClip>,
    failed: Vec<bool>,
    bytes: usize,
    resident: usize,
    clock: u64,
}

#[derive(Clone, Copy)]
struct ChannelMix {
    speed: f64,
    gain: [f32; 2],
}

impl ChannelMix {
    const DEFAULT: Self = Self {
        speed: 1.0,
        gain: [1.0; 2],
    };
}

struct Voice {
    id: i64,
    clip: ClipId,
    note: Option<usize>,
    program: u16,
    pedal_release: bool,
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
    region_filter: Option<LowPass>,
    pan_gain: [f32; 2],
    filtered: [f32; 2],
    looping: bool,
}

pub struct Mixer {
    channel_mix: [ChannelMix; 16],
    pedal: [u8; 16],
    standalone: Vec<Sample>,
    standalone_bytes: usize,
    voices: [Option<Voice>; POLYPHONY],
    steal_tails: [Option<Voice>; STEAL_TAILS],
    next_voice: i64,
    pending: [Option<(u16, u8)>; 16 * 128],
    bank: Option<StreamingBank>,
    pub(crate) effects: EffectChain,
}

impl Mixer {
    pub fn new() -> Self {
        Self {
            channel_mix: [ChannelMix::DEFAULT; 16],
            pedal: [0; 16],
            standalone: Vec::new(),
            standalone_bytes: 0,
            voices: std::array::from_fn(|_| None),
            steal_tails: std::array::from_fn(|_| None),
            next_voice: 1,
            pending: [None; 16 * 128],
            bank: None,
            effects: EffectChain::new(),
        }
    }

    pub fn prepare_output(&mut self, rate: u32, channels: usize) {
        self.effects.prepare(rate, channels);
    }

    pub fn set_channel_mix(&mut self, channel: usize, gain: f32, pan: f32, semitones: f32) {
        self.channel_mix[channel] = ChannelMix {
            speed: 2.0f64.powf(f64::from(semitones) / 12.0),
            gain: [gain * (1.0 - pan.max(0.0)), gain * (1.0 + pan.min(0.0))],
        };
    }

    pub fn set_pedal(&mut self, channel: usize, value: u8) {
        if self.pedal[channel] == value {
            return;
        }
        self.pedal[channel] = value;
        let Some(bank) = &self.bank else { return };
        for voice in self.voices.iter_mut().flatten().filter(|voice| {
            voice.pedal_release && voice.note.is_some_and(|key| key / 128 == channel)
        }) {
            voice.update_pedal(bank.index.programs.get(&voice.program), value);
        }
    }

    pub fn silence_channel(&mut self, channel: usize) {
        let keys = channel * 128..(channel + 1) * 128;
        self.pending[keys.clone()].fill(None);
        for slot in self.voices.iter_mut().chain(&mut self.steal_tails) {
            if slot
                .as_ref()
                .and_then(|voice| voice.note)
                .is_some_and(|key| keys.contains(&key))
            {
                *slot = None;
            }
        }
    }

    pub fn load(&mut self, sample: Sample) -> Result<usize, Sample> {
        let bytes = sample.pcm.capacity() * size_of::<i16>();
        if self.standalone_bytes + bytes > STANDALONE_PCM_BUDGET
            || self.standalone.len() == MAX_STANDALONE_CLIPS
        {
            return Err(sample);
        }
        let index = self.standalone.len();
        self.standalone.push(sample);
        self.standalone_bytes += bytes;
        Ok(index)
    }

    pub fn install(&mut self, index: Bank, queue: Sender<usize>) {
        self.bank = Some(StreamingBank {
            failed: vec![false; index.clips.len()],
            index,
            queue,
            cache: HashMap::with_capacity(
                MAX_CACHE_ENTRIES
                    + crate::streaming::QUEUE_CAPACITY
                    + crate::streaming::MAX_OPEN_DECODERS,
            ),
            bytes: 0,
            resident: 0,
            clock: 0,
        });
        for (key, trigger) in std::mem::replace(&mut self.pending, [None; 16 * 128])
            .into_iter()
            .enumerate()
        {
            if let Some((program, velocity)) = trigger {
                self.note(program, key, velocity);
            }
        }
    }

    pub fn note(&mut self, program: u16, key: usize, velocity: u8) {
        let Some(bank) = &self.bank else {
            self.pending[key] = (velocity != 0).then_some((program, velocity));
            return;
        };
        if velocity == 0 {
            for voice in self
                .voices
                .iter_mut()
                .flatten()
                .filter(|voice| voice.note == Some(key) && !voice.key_up)
            {
                voice.key_up = true;
                if voice.release == 0.0 && voice.damper > 0.0 {
                    voice.update_pedal(
                        bank.index.programs.get(&voice.program),
                        self.pedal[key / 128],
                    );
                }
            }
            return;
        }
        self.trigger(program, (key % 128) as u8, velocity, Some(key), false, 0);
    }

    pub fn pad(&mut self, program: u16, key: u8, velocity: u8, looping: bool, current: i64) -> i64 {
        if velocity == 0 {
            self.stop(current);
            return 0;
        }
        self.trigger(program, key, velocity, None, looping, current)
    }

    fn trigger(
        &mut self,
        program: u16,
        key: u8,
        velocity: u8,
        note: Option<usize>,
        looping: bool,
        current: i64,
    ) -> i64 {
        let Some(bank) = &self.bank else { return 0 };
        let program_id = program;
        let Some(program) = bank.index.programs.get(&program_id) else {
            return 0;
        };
        let valid = |clip: usize| !bank.failed[clip];
        let Some(zone) = program.resolve(key, velocity, valid) else {
            return 0;
        };
        let pedal_voice = note.and_then(|note| {
            let zone = program.pedal_zone(key, velocity, self.pedal[note / 128], valid)?;
            Some(Voice::region(
                zone,
                program,
                program_id,
                key,
                velocity,
                Some(note),
                false,
            ))
        });
        let voice = Voice::region(zone, program, program_id, key, velocity, note, looping);
        let clip = voice.clip;
        let retrigger = program.retrigger;
        let current_voice = looping
            .then(|| {
                self.voices
                    .iter()
                    .flatten()
                    .find(|voice| voice.id == current && voice.release == 0.0)
            })
            .flatten();
        if current_voice.is_some_and(|previous| {
            previous.clip == clip
                && previous.speed == voice.speed
                && previous.pan_gain == voice.pan_gain
                && previous.region_filter.as_ref().map(|filter| filter.hz) == zone.cutoff
        }) {
            self.gain(current, voice.gain);
            return current;
        }
        if !self.bank.as_mut().unwrap().request(zone.clip) {
            return current_voice.map_or(0, |_| current);
        }
        if current_voice.is_some() {
            self.stop(current);
        }
        if note.is_some() && retrigger > 0.0 {
            for slot in &mut self.voices {
                if let Some(old) = slot.as_mut().filter(|voice| voice.note == note) {
                    if old.position == 0.0 {
                        *slot = None;
                    } else if old.release == 0.0 || old.release > retrigger {
                        old.release = retrigger;
                        old.pedal_release = false;
                    }
                }
            }
        }
        let id = self.spawn(voice);
        if let Some(voice) = pedal_voice {
            if let ClipId::Bank(clip) = voice.clip {
                if self.bank.as_mut().unwrap().request(clip) {
                    self.spawn(voice);
                }
            }
        }
        id
    }

    pub fn fire(&mut self, clip: usize, gain: f32, speed: f64, looping: bool) -> i64 {
        if clip >= self.standalone.len() {
            return 0;
        }
        self.spawn(Voice::new(ClipId::Standalone(clip), gain, speed, looping))
    }

    // Charge the full clip before allowing any worker to allocate its PCM.
    pub fn reserve_decode(&mut self, id: usize) -> (bool, Vec<Arc<StreamPcm>>) {
        let Some(bank) = self.bank.as_mut() else {
            return (false, Vec::new());
        };
        let mut evicted = Vec::new();
        if !self
            .voices
            .iter()
            .flatten()
            .any(|voice| voice.clip == ClipId::Bank(id))
        {
            bank.cache.remove(&id);
            return (false, evicted);
        }
        let bytes = bank.index.clips[id].pcm_bytes();
        if !bank.has_room(bytes) {
            evicted = match self.evict_for_decode(bytes) {
                Some(evicted) => evicted,
                None => {
                    self.cancel_decode(id, false);
                    return (false, evicted);
                }
            };
        }
        let bank = self.bank.as_mut().unwrap();
        bank.cache.get_mut(&id).unwrap().state = ClipState::Reserved;
        bank.bytes += bytes;
        bank.resident += 1;
        (true, evicted)
    }

    fn evict_for_decode(&mut self, bytes: usize) -> Option<Vec<Arc<StreamPcm>>> {
        let bank = self.bank.as_mut()?;
        let pinned: HashSet<_> = self
            .voices
            .iter()
            .chain(&self.steal_tails)
            .flatten()
            .filter_map(|voice| match voice.clip {
                ClipId::Bank(id) => Some(id),
                ClipId::Standalone(_) => None,
            })
            .collect();
        let mut candidates: Vec<_> = bank
            .cache
            .iter()
            .filter(|&(&id, entry)| {
                matches!(entry.state, ClipState::Ready(_)) && !pinned.contains(&id)
            })
            .map(|(&id, entry)| (entry.age, id))
            .collect();
        let reclaimable: usize = candidates
            .iter()
            .map(|&(_, id)| bank.index.clips[id].pcm_bytes())
            .sum();
        if candidates.is_empty() || bank.bytes + bytes > CACHE_PCM_BUDGET + reclaimable {
            return None;
        }
        candidates.sort_unstable();
        let mut evicted = Vec::new();
        for (_, id) in candidates {
            if bank.has_room(bytes) {
                break;
            }
            if let ClipState::Ready(sample) = bank.cache.remove(&id).unwrap().state {
                bank.bytes -= bank.index.clips[id].pcm_bytes();
                bank.resident -= 1;
                evicted.push(sample);
            }
        }
        Some(evicted)
    }

    pub fn begin_decode(&mut self, id: usize, sample: Arc<StreamPcm>) {
        let bank = self.bank.as_mut().unwrap();
        bank.cache.get_mut(&id).unwrap().state = ClipState::Decoding(sample);
    }

    pub fn finish_decode(&mut self, id: usize, valid: bool) -> Option<Arc<StreamPcm>> {
        let bank = self.bank.as_mut()?;
        let entry = bank.cache.get_mut(&id)?;
        if valid
            && matches!(&entry.state, ClipState::Decoding(sample)
            if sample.ready().len() == bank.index.clips[id].frames * sample.channels)
        {
            if let ClipState::Decoding(sample) =
                std::mem::replace(&mut entry.state, ClipState::Queued)
            {
                entry.state = ClipState::Ready(sample);
            }
            return None;
        }
        self.cancel_decode(id, true)
    }

    fn cancel_decode(&mut self, id: usize, failed: bool) -> Option<Arc<StreamPcm>> {
        let bank = self.bank.as_mut()?;
        bank.failed[id] = failed;
        let entry = bank.cache.remove(&id)?;
        for slot in self.voices.iter_mut().chain(&mut self.steal_tails) {
            if slot
                .as_ref()
                .is_some_and(|voice| voice.clip == ClipId::Bank(id))
            {
                *slot = None;
            }
        }
        if !matches!(entry.state, ClipState::Queued) {
            bank.bytes -= bank.index.clips[id].pcm_bytes();
            bank.resident -= 1;
        }
        match entry.state {
            ClipState::Ready(sample) | ClipState::Decoding(sample) => Some(sample),
            _ => None,
        }
    }

    fn spawn(&mut self, mut voice: Voice) -> i64 {
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
        if let Some(mut stolen) = slot.replace(voice).filter(|voice| voice.position > 0.0) {
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
        if let Some(slot) = self
            .voices
            .iter_mut()
            .find(|slot| slot.as_ref().is_some_and(|voice| voice.id == id))
        {
            let voice = slot.as_mut().unwrap();
            if voice.position == 0.0 {
                *slot = None;
            } else {
                voice.release = 0.02;
                voice.pedal_release = false;
            }
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
        self.pending.fill(None);
        self.pedal.fill(0);
        self.voices.fill_with(|| None);
        self.steal_tails.fill_with(|| None);
        self.effects.clear();
    }

    pub fn release(&mut self) {
        self.pending.fill(None);
        for slot in &mut self.voices {
            if let Some(voice) = slot {
                if voice.position == 0.0 {
                    *slot = None;
                } else {
                    voice.release = 0.02;
                    voice.pedal_release = false;
                }
            }
        }
    }

    pub fn render<T: OutputSample + FromSample<f32>>(
        &mut self,
        output: &mut [T],
        channels: usize,
        rate: u32,
    ) {
        let rate = rate as f32;
        for chunk in output.chunks_mut(128 * channels) {
            let mut mix = [[0.0f32; 2]; 128];
            let frames = chunk.len() / channels;
            for slot in self.voices.iter_mut().chain(&mut self.steal_tails) {
                if let Some(voice) = slot {
                    let channel = voice
                        .note
                        .map_or(ChannelMix::DEFAULT, |key| self.channel_mix[key / 128]);
                    let alive = match voice.clip {
                        ClipId::Standalone(id) => {
                            let sample = &self.standalone[id];
                            voice.mix(
                                &sample.pcm,
                                sample.channels,
                                sample.rate,
                                true,
                                &mut mix[..frames],
                                rate,
                                channel,
                            )
                        }
                        ClipId::Bank(id) => {
                            let (sample, complete) =
                                match &self.bank.as_ref().unwrap().cache[&id].state {
                                    ClipState::Queued | ClipState::Reserved => continue,
                                    ClipState::Decoding(sample) => (sample, false),
                                    ClipState::Ready(sample) => (sample, true),
                                };
                            voice.mix(
                                sample.ready(),
                                sample.channels,
                                sample.rate,
                                complete,
                                &mut mix[..frames],
                                rate,
                                channel,
                            )
                        }
                    };
                    if !alive {
                        *slot = None;
                    }
                }
            }
            self.effects.process(&mut mix[..frames]);
            for (frame, [left, right]) in chunk.chunks_exact_mut(channels).zip(mix) {
                if channels == 1 {
                    frame[0] = T::from_sample((left + right) * 0.5);
                } else {
                    frame[0] = T::from_sample(left);
                    frame[1] = T::from_sample(right);
                    frame[2..].fill(T::from_sample(0.0));
                }
            }
        }
    }
}

impl StreamingBank {
    fn has_room(&self, bytes: usize) -> bool {
        self.bytes + bytes <= CACHE_PCM_BUDGET && self.resident < MAX_CACHE_ENTRIES
    }

    fn request(&mut self, id: usize) -> bool {
        if self.index.clips[id].pcm_bytes() > CACHE_PCM_BUDGET {
            return false;
        }
        self.clock += 1;
        if let Some(entry) = self.cache.get_mut(&id) {
            entry.age = self.clock;
            return true;
        }
        if self.queue.try_send(id).is_err() {
            return false;
        }
        self.cache.insert(
            id,
            CachedClip {
                age: self.clock,
                state: ClipState::Queued,
            },
        );
        true
    }
}

trait PcmValue {
    fn value(&self) -> i16;
}

impl PcmValue for i16 {
    fn value(&self) -> i16 {
        *self
    }
}

impl PcmValue for AtomicI16 {
    fn value(&self) -> i16 {
        self.load(Relaxed)
    }
}

impl Voice {
    fn new(clip: ClipId, gain: f32, speed: f64, looping: bool) -> Self {
        Self {
            id: 0,
            clip,
            note: None,
            program: 0,
            pedal_release: false,
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
            region_filter: None,
            pan_gain: [1.0; 2],
            filtered: [0.0; 2],
            looping,
        }
    }

    fn region(
        zone: Zone,
        program: &Program,
        program_id: u16,
        key: u8,
        velocity: u8,
        note: Option<usize>,
        looping: bool,
    ) -> Self {
        let semitones = f64::from(key) - f64::from(zone.root) + f64::from(zone.pitch_cents) / 100.0;
        Self {
            note,
            program: program_id,
            pan_gain: [1.0 - zone.pan.max(0.0), 1.0 + zone.pan.min(0.0)],
            damper: zone.release,
            cutoff: program.cutoff(velocity),
            region_filter: zone.cutoff.map(LowPass::new),
            attack: zone.attack.unwrap_or(program.attack),
            ..Self::new(
                ClipId::Bank(zone.clip),
                zone.gain * program.velocity_gain(velocity),
                2f64.powf(semitones / 12.0),
                looping,
            )
        }
    }

    fn update_pedal(&mut self, program: Option<&Program>, pedal: u8) {
        if let Some(curve) = program.and_then(|program| program.pedal_release.as_ref()) {
            self.release = self.damper + curve[usize::from(pedal)];
            self.pedal_release = true;
        } else {
            self.pedal_release = pedal >= 64;
            self.release = if self.pedal_release { 0.0 } else { self.damper };
        }
    }

    fn mix(
        &mut self,
        pcm: &[impl PcmValue],
        channels: usize,
        sample_rate: u32,
        complete: bool,
        output: &mut [[f32; 2]],
        rate: f32,
        channel: ChannelMix,
    ) -> bool {
        let frames = pcm.len() / channels;
        let step = self.speed * channel.speed * f64::from(sample_rate) / f64::from(rate);
        let attack_step = 1.0 / (rate * self.attack);
        let release_step = if self.release > 0.0 {
            1.0 / (rate * self.release)
        } else {
            0.0
        };
        let smoothing = 1.0 / (rate * 0.01);
        let filter = if let Some(region_filter) = &mut self.region_filter {
            region_filter.prepare(rate);
            0.0
        } else if self.cutoff > 0.0 {
            1.0 - (-std::f32::consts::TAU * self.cutoff / rate).exp()
        } else {
            0.0
        };
        let left_gain = channel.gain[0] * self.pan_gain[0];
        let right_gain = channel.gain[1] * self.pan_gain[1];
        let mut peak: f32 = 0.0;
        for [left, right] in output {
            if !complete && self.position as usize + 1 >= frames {
                // Keep the interpolation cursor at the next available pair.
                break;
            }
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
            let read = |channel: usize| {
                let a = f32::from(pcm[index * channels + channel].value());
                let b = f32::from(pcm[next * channels + channel].value());
                a + fraction * (b - a)
            };
            let mut frame = [read(0), read(channels - 1)];
            if let Some(region_filter) = &mut self.region_filter {
                region_filter.process(&mut frame);
            } else if filter > 0.0 {
                for (value, previous) in frame.iter_mut().zip(&mut self.filtered) {
                    *previous += filter * (*value - *previous);
                    *value = *previous;
                }
            }
            let voice_left = frame[0] * amplitude * left_gain;
            let voice_right = frame[1] * amplitude * right_gain;
            peak = peak.max(voice_left.abs()).max(voice_right.abs());
            *left += voice_left;
            *right += voice_right;
            self.position += step;
        }
        self.peak = peak;
        true
    }
}
