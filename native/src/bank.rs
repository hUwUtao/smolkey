use std::io::{BufRead, BufReader, Cursor, Read};

use ogg::reading::PacketReader;
use opus::{Channels, Decoder};
use rayon::prelude::*;

pub const RATE: u32 = 48_000;
pub const DRUMS: usize = 6;
pub const MAX_ENCODED_BYTES: usize = 32 * 1024 * 1024;
pub const PCM_BUDGET: usize = 128 * 1024 * 1024;
const MAX_BANK_CLIPS: usize = 256;
const MAX_BANK_SECONDS: usize = 8;
const MAX_DECODE_WORKERS: usize = 4;
const MAX_HEADER_BYTES: u64 = 4096;

pub struct Sample {
    pub pcm: Box<[i16]>,
    pub channels: usize,
    pub rate: u32,
}

impl Sample {
    pub fn frames(&self) -> usize {
        self.pcm.len() / self.channels
    }
}

/// Decode one complete, unchained Ogg Opus stream (mapping family 0).
pub fn decode(data: &[u8], max_frames: usize) -> Option<Sample> {
    decode_at_rate(data, RATE, max_frames)
}

fn decode_at_rate(data: &[u8], rate: u32, max_frames: usize) -> Option<Sample> {
    let divisor = (RATE / rate) as usize;
    let mut packets = PacketReader::new(Cursor::new(data));
    let head = packets.read_packet().ok()??;
    if head.data.len() != 19
        || &head.data[..8] != b"OpusHead"
        || head.data[8] != 1
        || head.data[18] != 0
        || !head.first_in_stream()
    {
        return None;
    }
    let channels = head.data[9] as usize;
    let layout = match channels {
        1 => Channels::Mono,
        2 => Channels::Stereo,
        _ => return None,
    };
    let skip = u16::from_le_bytes(head.data[10..12].try_into().ok()?) as usize;
    let gain = i16::from_le_bytes(head.data[16..18].try_into().ok()?);
    let tags = packets.read_packet().ok()??;
    if tags.stream_serial() != head.stream_serial() || !tags.data.starts_with(b"OpusTags") {
        return None;
    }
    let mut decoder = Decoder::new(rate, layout).ok()?;
    decoder.set_gain(i32::from(gain)).ok()?;
    let mut buffer = [0; 5760 * 2]; // Maximum Opus packet duration at 48 kHz is 120 ms.
    let mut pcm = Vec::with_capacity((data.len() * 8).min(max_frames * channels));
    let mut position = 0usize;
    let mut origin = None;
    while let Some(packet) = packets.read_packet().ok()? {
        if packet.stream_serial() != head.stream_serial() || packet.data.is_empty() {
            return None;
        }
        let frames = decoder
            .decode(&packet.data, &mut buffer[..5760 * channels], false)
            .ok()?;
        let next = position + frames * divisor;
        let last = packet.last_in_stream();
        let end = if packet.last_in_page() {
            let granule = usize::try_from(packet.absgp_page()).ok()?;
            let origin = *origin.get_or_insert_with(|| granule.saturating_sub(next));
            let end = granule.checked_sub(origin)?;
            if end > next || (!last && end != next) {
                return None;
            }
            end
        } else {
            next
        };
        // Ogg granules and pre-skip are always expressed on the 48 kHz timeline.
        let end_frame = end.div_ceil(divisor);
        let skip_frame = skip.div_ceil(divisor);
        let length = end_frame.saturating_sub(skip_frame);
        if length > max_frames {
            return None;
        }
        let first_frame = position / divisor;
        let start = skip_frame.max(first_frame);
        if end_frame > start {
            pcm.extend_from_slice(
                &buffer[(start - first_frame) * channels..(end_frame - first_frame) * channels],
            );
        }
        if last {
            pcm.truncate(length * channels);
            if pcm.is_empty() || packets.read_packet().ok()?.is_some() {
                return None;
            }
            return Some(Sample {
                pcm: pcm.into_boxed_slice(),
                channels,
                rate,
            });
        }
        position = next;
    }
    None
}

#[derive(Clone, Copy)]
pub struct Zone {
    pub low: u8,
    pub high: u8,
    pub root: u8,
    pub layer: u8,
    pub release_ms: u16,
    pub gain_centidb: i16,
}

impl Zone {
    fn read(bytes: &[u8]) -> Option<Self> {
        let mut fields = std::str::from_utf8(bytes).ok()?.split_ascii_whitespace();
        if fields.next()? != "zone" {
            return None;
        }
        let low: u8 = fields.next()?.parse().ok()?;
        let high: u8 = fields.next()?.parse().ok()?;
        let root: u8 = fields.next()?.parse().ok()?;
        let layer: u8 = fields.next()?.parse().ok()?;
        let release_ms = fields.next()?.parse().ok()?;
        let gain_centidb = fields.next()?.parse().ok()?;
        if fields.next().is_some() || !(-9600..=1200).contains(&gain_centidb) {
            return None;
        }
        if low > high || layer >= 4 {
            return None;
        }
        if high >= 128
            && !(low == high && low == root && (low as usize) < 128 + DRUMS && layer == 0)
        {
            return None;
        }
        if high < 128 && root >= 128 {
            return None;
        }
        Some(Self {
            low,
            high,
            root,
            layer,
            release_ms,
            gain_centidb,
        })
    }
}

pub struct Clip {
    pub zones: Vec<Zone>,
    pub sample: Sample,
}

pub struct Playback {
    pub piano_velocity_max: [u8; 3],
    pub piano_gain: f32,
    pub piano_velocity_exponent: f32,
    pub piano_attack: f32,
    pub piano_restrike: f32,
    pub piano_soft_velocity: u8,
    pub piano_soft_hz: [f32; 2],
    pub drum_velocity_exponent: f32,
    pub drum_attack: f32,
}

impl Playback {
    fn read(header: &[u8]) -> Option<Self> {
        let mut lines = std::str::from_utf8(header).ok()?.lines();
        let mut field = |name| {
            let (key, value) = lines.next()?.split_once(' ')?;
            (key == name).then_some(value)
        };
        let [low, middle, high] = numbers(field("piano_velocity_max")?)?;
        if !(0.0 < low && low < middle && middle < high && high < 127.0)
            || [low, middle, high].iter().any(|n| n.fract() != 0.0)
        {
            return None;
        }
        let [piano_gain] = numbers(field("piano_gain")?)?;
        let [piano_velocity_exponent] = numbers(field("piano_velocity_exponent")?)?;
        let [piano_attack_ms] = numbers(field("piano_attack_ms")?)?;
        let [piano_restrike_ms] = numbers(field("piano_restrike_ms")?)?;
        let [soft_velocity, min_hz, max_hz] = numbers(field("piano_soft_filter")?)?;
        let [drum_velocity_exponent] = numbers(field("drum_velocity_exponent")?)?;
        let [drum_attack_ms] = numbers(field("drum_attack_ms")?)?;
        if lines.next().is_some()
            || !(0.0..=2.0).contains(&piano_gain)
            || !(0.1..=8.0).contains(&piano_velocity_exponent)
            || !(0.1..=8.0).contains(&drum_velocity_exponent)
            || !(0.1..=1000.0).contains(&piano_attack_ms)
            || !(0.1..=1000.0).contains(&piano_restrike_ms)
            || !(0.1..=1000.0).contains(&drum_attack_ms)
            || !(2.0..=127.0).contains(&soft_velocity)
            || soft_velocity.fract() != 0.0
            || !(20.0..=max_hz).contains(&min_hz)
            || max_hz > 24_000.0
        {
            return None;
        }
        Some(Self {
            piano_velocity_max: [low as u8, middle as u8, high as u8],
            piano_gain,
            piano_velocity_exponent,
            piano_attack: piano_attack_ms / 1000.0,
            piano_restrike: piano_restrike_ms / 1000.0,
            piano_soft_velocity: soft_velocity as u8,
            piano_soft_hz: [min_hz, max_hz],
            drum_velocity_exponent,
            drum_attack: drum_attack_ms / 1000.0,
        })
    }
}

fn numbers<const N: usize>(value: &str) -> Option<[f32; N]> {
    let mut fields = value.split_ascii_whitespace();
    let mut values = [0.0f32; N];
    for number in &mut values {
        *number = fields.next()?.parse().ok()?;
        if !number.is_finite() {
            return None;
        }
    }
    fields.next().is_none().then_some(values)
}

fn read_header(input: &mut impl BufRead, header: &mut Vec<u8>) -> Option<()> {
    header.clear();
    let mut input = input.take(MAX_HEADER_BYTES);
    while input.read_until(b'\n', header).ok()? != 0 {
        if header.ends_with(b"\n\n") {
            header.truncate(header.len() - 2);
            return Some(());
        }
    }
    None
}

struct Record {
    rate: u32,
    zones: Vec<Zone>,
    opus: Vec<u8>,
}

impl Record {
    fn read(input: &mut impl BufRead, header: &mut Vec<u8>) -> Option<Self> {
        read_header(input, header)?;
        let mut lines = header.split(|byte| *byte == b'\n');
        let (rate, length) = clip_header(lines.next()?)?;
        let mut opus = Vec::new();
        input.take(length as u64).read_to_end(&mut opus).ok()?;
        if opus.len() != length {
            return None;
        }
        Some(Self {
            rate,
            zones: lines.filter_map(Zone::read).collect(),
            opus,
        })
    }

    fn decode(self) -> Option<Clip> {
        if self.zones.is_empty() || !matches!(self.rate, 24_000 | RATE) {
            return None;
        }
        let sample = decode_at_rate(&self.opus, self.rate, MAX_BANK_SECONDS * self.rate as usize)?;
        Some(Clip {
            zones: self.zones,
            sample,
        })
    }
}

/// Read and decode bounded batches, delivering valid clips in SMB3 file order.
pub fn decode_bank(input: impl Read, mut consume: impl FnMut(Clip)) -> Option<Playback> {
    let mut input = BufReader::new(input.take(MAX_ENCODED_BYTES as u64));
    let mut magic = [0; 5];
    input.read_exact(&mut magic).ok()?;
    if &magic != b"SMB3\n" {
        return None;
    }
    let mut header = Vec::new();
    read_header(&mut input, &mut header)?;
    let playback = Playback::read(&header)?;

    let workers =
        std::thread::available_parallelism().map_or(1, |n| n.get().min(MAX_DECODE_WORKERS));
    let pool = (workers > 1)
        .then(|| {
            rayon::ThreadPoolBuilder::new()
                .num_threads(workers)
                .build()
                .ok()
        })
        .flatten();
    let mut records = std::iter::from_fn(|| Record::read(&mut input, &mut header))
        .fuse()
        .take(MAX_BANK_CLIPS);
    loop {
        let batch: Vec<_> = records.by_ref().take(workers).collect();
        if batch.is_empty() {
            break;
        }
        if let Some(pool) = &pool {
            let clips = pool.install(|| {
                batch
                    .into_par_iter()
                    .map(Record::decode)
                    .collect::<Vec<_>>()
            });
            clips.into_iter().flatten().for_each(&mut consume);
        } else {
            batch
                .into_iter()
                .filter_map(Record::decode)
                .for_each(&mut consume);
        }
    }
    Some(playback)
}

fn clip_header(bytes: &[u8]) -> Option<(u32, usize)> {
    let mut fields = std::str::from_utf8(bytes).ok()?.split_ascii_whitespace();
    if fields.next()? != "clip" {
        return None;
    }
    let rate = fields.next()?.parse().ok()?;
    let length = fields.next()?.parse().ok()?;
    fields.next().is_none().then_some((rate, length))
}
