use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, Cursor, Read, Seek, SeekFrom};
use std::os::unix::fs::FileExt;
use std::sync::Arc;
use std::sync::atomic::{
    AtomicI16, AtomicUsize,
    Ordering::{Acquire, Relaxed, Release},
};

use ogg::reading::PacketReader;
use opus::{Channels, Decoder};

pub const RATE: u32 = 48_000;
pub const MAX_ENCODED_CLIP_BYTES: usize = 32 * 1024 * 1024;
const MAX_INDEX_BYTES: usize = 8 * 1024 * 1024;
const MAX_CLIP_SECONDS: usize = 300;
const PCM_CHUNK_FRAMES: usize = 2048;

pub struct Sample {
    pub pcm: Vec<i16>,
    pub channels: usize,
    pub rate: u32,
}

pub struct StreamPcm {
    samples: Box<[AtomicI16]>,
    available: AtomicUsize,
    pub channels: usize,
    pub rate: u32,
}

impl StreamPcm {
    pub fn ready(&self) -> &[AtomicI16] {
        &self.samples[..self.available.load(Acquire)]
    }
}

// One writer travels with the decoder, even when its task resumes on another thread.
pub struct PcmWriter(Arc<StreamPcm>);

impl PcmWriter {
    pub fn new(clip: Clip) -> Self {
        Self(Arc::new(StreamPcm {
            samples: (0..clip.frames * clip.channels)
                .map(|_| AtomicI16::new(0))
                .collect(),
            available: AtomicUsize::new(0),
            channels: clip.channels,
            rate: clip.rate,
        }))
    }

    pub fn reader(&self) -> Arc<StreamPcm> {
        Arc::clone(&self.0)
    }

    pub fn append(&mut self, pcm: &[i16]) -> Option<()> {
        let start = self.0.available.load(Relaxed);
        let end = start.checked_add(pcm.len())?;
        for (dst, &sample) in self.0.samples.get(start..end)?.iter().zip(pcm) {
            dst.store(sample, Relaxed);
        }
        // After this release, the mixer can read the entire initialized prefix.
        self.0.available.store(end, Release);
        Some(())
    }
}

/// Decode one complete, unchained Ogg Opus stream (mapping family 0).
pub fn decode(data: &[u8], max_frames: usize) -> Option<Sample> {
    let mut pcm = Vec::new();
    let channels = decode_stream(Cursor::new(data), RATE, max_frames, |_, chunk| {
        pcm.extend_from_slice(chunk);
        Some(())
    })?;
    Some(Sample {
        pcm,
        channels,
        rate: RATE,
    })
}

/// Decode Ogg Opus on its original timeline, publishing bounded batches of PCM.
pub fn decode_stream(
    input: impl Read + Seek,
    rate: u32,
    max_frames: usize,
    mut publish: impl FnMut(usize, &[i16]) -> Option<()>,
) -> Option<usize> {
    let mut stream = OpusStream::open(input, rate, max_frames)?;
    let mut pcm = Vec::new();
    loop {
        let finished = stream.read_chunk(PCM_CHUNK_FRAMES, &mut pcm)?;
        publish(stream.channels, &pcm)?;
        if finished {
            return Some(stream.channels);
        }
    }
}

/// A decoder can yield after publishing a chunk and resume with its packet and Opus state intact.
pub struct OpusStream<R: Read + Seek> {
    packets: PacketReader<R>,
    decoder: Decoder,
    serial: u32,
    pub channels: usize,
    divisor: usize,
    skip_frame: usize,
    max_frames: usize,
    position: usize,
    origin: Option<usize>,
}

impl<R: Read + Seek> OpusStream<R> {
    pub fn open(input: R, rate: u32, max_frames: usize) -> Option<Self> {
        let divisor = (RATE / rate) as usize;
        let mut packets = PacketReader::new(input);
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
        let serial = head.stream_serial();
        let tags = packets.read_packet().ok()??;
        if tags.stream_serial() != serial || !tags.data.starts_with(b"OpusTags") {
            return None;
        }
        let mut decoder = Decoder::new(rate, layout).ok()?;
        decoder.set_gain(i32::from(gain)).ok()?;
        Some(Self {
            packets,
            decoder,
            serial,
            channels,
            divisor,
            skip_frame: skip.div_ceil(divisor),
            max_frames,
            position: 0,
            origin: None,
        })
    }

    /// Returns true at a clean end of stream and None on invalid input.
    pub fn read_chunk(&mut self, target_frames: usize, pcm: &mut Vec<i16>) -> Option<bool> {
        pcm.clear();
        let channels = self.channels;
        let mut buffer = [0; 5760 * 2]; // A maximum-duration Opus packet at 48 kHz.
        loop {
            let packet = self.packets.read_packet().ok()??;
            if packet.stream_serial() != self.serial || packet.data.is_empty() {
                return None;
            }
            let frames = self
                .decoder
                .decode(&packet.data, &mut buffer[..5760 * channels], false)
                .ok()?;
            let next = self.position + frames * self.divisor;
            let last = packet.last_in_stream();
            let end = if packet.last_in_page() {
                let granule = usize::try_from(packet.absgp_page()).ok()?;
                let origin = *self
                    .origin
                    .get_or_insert_with(|| granule.saturating_sub(next));
                let end = granule.checked_sub(origin)?;
                if end > next || (!last && end != next) {
                    return None;
                }
                end
            } else {
                next
            };
            // Ogg granules and pre-skip are always expressed on the 48 kHz timeline.
            let end_frame = end.div_ceil(self.divisor);
            let length = end_frame.saturating_sub(self.skip_frame);
            if length > self.max_frames {
                return None;
            }
            let first_frame = self.position / self.divisor;
            let start = self.skip_frame.max(first_frame);
            if end_frame > start {
                pcm.extend_from_slice(
                    &buffer[(start - first_frame) * channels..(end_frame - first_frame) * channels],
                );
            }
            self.position = next;
            if last {
                return (length != 0 && self.packets.read_packet().ok()?.is_none()).then_some(true);
            }
            if pcm.len() >= target_frames * channels {
                return Some(false);
            }
        }
    }
}

#[derive(Clone, Copy)]
pub struct Clip {
    offset: u64,
    bytes: u64,
    pub rate: u32,
    pub channels: usize,
    pub frames: usize,
}

impl Clip {
    pub fn pcm_bytes(self) -> usize {
        self.frames * self.channels * size_of::<i16>()
    }

    pub fn stream(self, input: Arc<File>) -> BufReader<FileRegion> {
        BufReader::new(FileRegion {
            input,
            offset: self.offset,
            length: self.bytes,
            position: 0,
        })
    }
}

// Each decoder has its own cursor. The file descriptor can be shared across workers.
pub struct FileRegion {
    input: Arc<File>,
    offset: u64,
    length: u64,
    position: u64,
}

impl Read for FileRegion {
    fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
        let len = (self.length - self.position).min(output.len() as u64) as usize;
        let read = self
            .input
            .read_at(&mut output[..len], self.offset + self.position)?;
        self.position += read as u64;
        Ok(read)
    }
}

impl Seek for FileRegion {
    fn seek(&mut self, seek: SeekFrom) -> std::io::Result<u64> {
        self.position = match seek {
            SeekFrom::Start(n) => Some(n),
            SeekFrom::Current(n) => self.position.checked_add_signed(n),
            SeekFrom::End(n) => self.length.checked_add_signed(n),
        }
        .filter(|&n| n <= self.length)
        .ok_or(std::io::ErrorKind::InvalidInput)?;
        Ok(self.position)
    }
}

#[derive(Clone, Copy)]
pub struct Zone {
    pub clip: usize,
    pub low: u8,
    pub high: u8,
    pub root: u8,
    pub velocity_low: u8,
    pub velocity_high: u8,
    pub release: f32,
    pub gain: f32,
    pub pitch_cents: i16,
    pub pan: f32,
    pub cutoff: Option<f32>,
    pub attack: Option<f32>,
    pub pedal_min: u8,
}

pub struct Program {
    pub gain: f32,
    pub velocity_exponent: f32,
    pub attack: f32,
    pub retrigger: f32,
    pub filter_velocity: u8,
    pub filter_hz: [f32; 2],
    pub fill_gaps: bool,
    pub zones: Vec<Zone>,
    pedal_zones: Vec<Zone>,
    pub pedal_release: Option<Box<[f32; 128]>>,
}

impl Program {
    pub fn resolve(&self, key: u8, velocity: u8, valid: impl Fn(usize) -> bool) -> Option<Zone> {
        let mut zones = self.zones.iter().filter(|zone| valid(zone.clip));
        zones
            .find(|zone| {
                (zone.low..=zone.high).contains(&key)
                    && (zone.velocity_low..=zone.velocity_high).contains(&velocity)
            })
            .copied()
            .or_else(|| {
                if !self.fill_gaps {
                    return None;
                }
                self.zones
                    .iter()
                    .filter(|zone| valid(zone.clip))
                    .min_by_key(|zone| {
                        let nearest_velocity =
                            velocity.clamp(zone.velocity_low, zone.velocity_high);
                        (velocity.abs_diff(nearest_velocity), key.abs_diff(zone.root))
                    })
                    .copied()
            })
    }

    pub fn pedal_zone(
        &self,
        key: u8,
        velocity: u8,
        pedal: u8,
        valid: impl Fn(usize) -> bool,
    ) -> Option<Zone> {
        self.pedal_zones
            .iter()
            .find(|zone| {
                pedal >= zone.pedal_min
                    && valid(zone.clip)
                    && (zone.low..=zone.high).contains(&key)
                    && (zone.velocity_low..=zone.velocity_high).contains(&velocity)
            })
            .copied()
    }

    pub fn velocity_gain(&self, velocity: u8) -> f32 {
        self.gain * (f32::from(velocity) / 127.0).powf(self.velocity_exponent)
    }

    pub fn cutoff(&self, velocity: u8) -> f32 {
        if velocity > self.filter_velocity {
            return 0.0;
        }
        let [low, high] = self.filter_hz;
        low * (high / low).powf(f32::from(velocity - 1) / f32::from(self.filter_velocity - 1))
    }
}

pub struct Bank {
    pub clips: Arc<[Clip]>,
    pub programs: HashMap<u16, Program>,
}

impl Bank {
    /// Read only the index using an independent cursor over a shared file descriptor.
    pub fn read_file(input: Arc<File>, start: u64, length: u64) -> Option<Self> {
        let reader = FileRegion {
            input,
            offset: start,
            length,
            position: 0,
        };
        Self::read_index(reader, start, length)
    }

    fn read_index(mut input: impl Read, start: u64, length: u64) -> Option<Self> {
        if length < 8 {
            return None;
        }
        let end = start.checked_add(length)?;
        let mut header = [0; 8];
        input.read_exact(&mut header).ok()?;
        if &header[..4] != b"SMB4" {
            return None;
        }
        let bytes = u32::from_le_bytes(header[4..].try_into().ok()?) as usize;
        let mut offset = start.checked_add(8 + bytes as u64)?;
        if bytes > MAX_INDEX_BYTES || offset > end {
            return None;
        }
        let mut index = vec![0; bytes];
        input.read_exact(&mut index).ok()?;
        let mut clips = Vec::new();
        let mut programs = HashMap::new();
        for line in std::str::from_utf8(&index).ok()?.lines() {
            let mut fields = line.split_ascii_whitespace();
            match fields.next()? {
                "clip" => {
                    let rate = field(&mut fields, 24_000..=RATE)?;
                    let channels = field(&mut fields, 1..=2)?;
                    let frames = field(&mut fields, 1..=MAX_CLIP_SECONDS * rate as usize)?;
                    let bytes = field(&mut fields, 1..=MAX_ENCODED_CLIP_BYTES)? as u64;
                    if !matches!(rate, 24_000 | RATE) {
                        return None;
                    }
                    clips.push(Clip {
                        offset,
                        bytes,
                        rate,
                        channels,
                        frames,
                    });
                    offset = offset.checked_add(bytes)?;
                    if offset > end {
                        return None;
                    }
                }
                "program" => {
                    let id = field(&mut fields, 0..=u16::MAX)?;
                    let gain = field(&mut fields, 0.0..=2.0)?;
                    let velocity_exponent = field(&mut fields, 0.1..=8.0)?;
                    let attack = field(&mut fields, 0.1..=1000.0)? / 1000.0;
                    let retrigger = field(&mut fields, 0.0..=1000.0)? / 1000.0;
                    let filter_velocity = field(&mut fields, 0..=127)?;
                    let low = field(&mut fields, 20.0..=24_000.0)?;
                    let high = field(&mut fields, low..=24_000.0)?;
                    let fill_gaps = field(&mut fields, 0..=1)? == 1;
                    if filter_velocity == 1
                        || programs
                            .insert(
                                id,
                                Program {
                                    gain,
                                    velocity_exponent,
                                    attack,
                                    retrigger,
                                    filter_velocity,
                                    filter_hz: [low, high],
                                    fill_gaps,
                                    zones: Vec::new(),
                                    pedal_zones: Vec::new(),
                                    pedal_release: None,
                                },
                            )
                            .is_some()
                    {
                        return None;
                    }
                }
                "pedal" => {
                    let id = field(&mut fields, 0..=u16::MAX)?;
                    let seconds = field(&mut fields, 0.0..=100.0)?;
                    let program = programs.get_mut(&id)?;
                    if program.pedal_release.is_some() {
                        return None;
                    }
                    program.pedal_release = Some(read_pedal_curve(&mut fields, seconds)?);
                }
                "zone" => {
                    let id = field(&mut fields, 0..=u16::MAX)?;
                    let clip = field(&mut fields, 0..=clips.len().checked_sub(1)?)?;
                    let low = field(&mut fields, 0..=127)?;
                    let high = field(&mut fields, low..=127)?;
                    let root = field(&mut fields, 0..=127)?;
                    let velocity_low = field(&mut fields, 1..=127)?;
                    let velocity_high = field(&mut fields, velocity_low..=127)?;
                    let release = f32::from(field(&mut fields, 0..=u16::MAX)?) / 1000.0;
                    let gain = 10f32.powf(field(&mut fields, -9600.0..=1200.0)? / 2000.0);
                    let (pitch_cents, pan, cutoff, attack, pedal_min) =
                        if let Some(pitch) = fields.next() {
                            let pitch: i16 = pitch.parse().ok()?;
                            if !(-12_800..=12_800).contains(&pitch) {
                                return None;
                            }
                            let pan = field(&mut fields, -100.0..=100.0)? / 100.0;
                            let cutoff = fields.next().map(str::parse::<f32>);
                            let cutoff = match cutoff {
                                None => None,
                                Some(Ok(0.0)) => None,
                                Some(Ok(hz)) if (20.0..=24_000.0).contains(&hz) => Some(hz),
                                _ => return None,
                            };
                            let (attack, pedal_min) = if let Some(attack) = fields.next() {
                                let ms: f32 = attack.parse().ok()?;
                                if !(0.0..=1000.0).contains(&ms) {
                                    return None;
                                }
                                (
                                    (ms > 0.0).then_some(ms / 1000.0),
                                    field(&mut fields, 0..=127)?,
                                )
                            } else {
                                (None, 0)
                            };
                            (pitch, pan, cutoff, attack, pedal_min)
                        } else {
                            (0, 0.0, None, None, 0)
                        };
                    let program = programs.get_mut(&id)?;
                    let zones = if pedal_min == 0 {
                        &mut program.zones
                    } else {
                        &mut program.pedal_zones
                    };
                    zones.push(Zone {
                        clip,
                        low,
                        high,
                        root,
                        velocity_low,
                        velocity_high,
                        release,
                        gain,
                        pitch_cents,
                        pan,
                        cutoff,
                        attack,
                        pedal_min,
                    });
                }
                _ => return None,
            }
            if fields.next().is_some() {
                return None;
            }
        }
        (offset == end && !clips.is_empty() && programs.values().any(|p| !p.zones.is_empty())).then(
            || Self {
                clips: clips.into(),
                programs,
            },
        )
    }
}

fn read_pedal_curve(
    fields: &mut std::str::SplitAsciiWhitespace<'_>,
    seconds: f32,
) -> Option<Box<[f32; 128]>> {
    field(fields, 0usize..=0)?;
    let mut first = 0;
    let mut value: f32 = field(fields, 0.0..=1.0)?;
    let mut table = Box::new([0.0; 128]);
    table[0] = value * seconds;
    while first < 127 {
        let last = field(fields, first + 1..=127)?;
        let next: f32 = field(fields, 0.0..=1.0)?;
        for (index, entry) in table.iter_mut().enumerate().take(last + 1).skip(first + 1) {
            let fraction = (index - first) as f32 / (last - first) as f32;
            *entry = (value + (next - value) * fraction) * seconds;
        }
        first = last;
        value = next;
    }
    Some(table)
}

fn field<T: std::str::FromStr + PartialOrd>(
    fields: &mut std::str::SplitAsciiWhitespace<'_>,
    range: std::ops::RangeInclusive<T>,
) -> Option<T> {
    let value = fields.next()?.parse().ok()?;
    range.contains(&value).then_some(value)
}
