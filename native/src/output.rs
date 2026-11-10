use std::collections::VecDeque;
use std::sync::atomic::{
    AtomicBool, AtomicI64, AtomicU8,
    Ordering::{Acquire, Relaxed, Release},
};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Error, ErrorKind, FromSample, Sample, SampleFormat, SizedSample, Stream, StreamConfig};

use crate::mixer::Mixer;

const STOPPED: u8 = 0;
const SUSPENDED: u8 = 1;
const ACTIVE: u8 = 2;
const RENDER_RESERVE_MS: u32 = 4;
const FIRST_RETRY: Duration = Duration::from_millis(100);

pub struct Output {
    state: Arc<AtomicU8>,
    thread: JoinHandle<()>,
    format: Arc<AtomicI64>,
}

impl Output {
    pub fn start(mixer: Arc<Mutex<Mixer>>) -> std::io::Result<Self> {
        let state = Arc::new(AtomicU8::new(ACTIVE));
        let worker_state = Arc::clone(&state);
        let format = Arc::new(AtomicI64::new(0));
        let worker_format = Arc::clone(&format);
        let thread = thread::Builder::new()
            .name("EnsembleOutput".into())
            .spawn(move || supervise(mixer, worker_state, worker_format))?;
        Ok(Self {
            state,
            thread,
            format,
        })
    }

    pub fn set_active(&self, active: bool) {
        self.state
            .store(if active { ACTIVE } else { SUSPENDED }, Release);
        self.thread.thread().unpark();
    }

    pub fn format(&self) -> i64 {
        if self.state.load(Acquire) == SUSPENDED {
            return -2;
        }
        self.format.load(Acquire)
    }

    pub fn close(self) {
        self.state.store(STOPPED, Release);
        self.thread.thread().unpark();
        let _ = self.thread.join();
    }
}

#[derive(Default)]
struct StreamHealth {
    failed: AtomicBool,
    heartbeat: AtomicBool,
}

fn supervise(mixer: Arc<Mutex<Mixer>>, state: Arc<AtomicU8>, format: Arc<AtomicI64>) {
    let mut retry = FIRST_RETRY;
    loop {
        match state.load(Acquire) {
            STOPPED => return,
            SUSPENDED => {
                retry = FIRST_RETRY;
                format.store(0, Release);
                thread::park();
                continue;
            }
            _ => {}
        }
        let health = Arc::new(StreamHealth::default());
        match open(&mixer, &health) {
            Ok((stream, negotiated)) => {
                format.store(negotiated, Release);
                let mut last_callback = Instant::now();
                while state.load(Acquire) == ACTIVE {
                    thread::park_timeout(Duration::from_millis(100));
                    if health.heartbeat.swap(false, Relaxed) {
                        last_callback = Instant::now();
                        retry = FIRST_RETRY;
                    }
                    if health.failed.load(Relaxed)
                        || last_callback.elapsed() >= Duration::from_secs(2)
                    {
                        log::warn!("Sampler output interrupted; reopening default device");
                        break;
                    }
                }
                drop(stream);
            }
            Err(error) => log::warn!("Could not open sampler output: {error}"),
        }
        format.store(-1, Release);
        if state.load(Acquire) == ACTIVE {
            thread::park_timeout(retry);
            retry = (retry * 2).min(Duration::from_secs(2));
        }
    }
}

fn open(mixer: &Arc<Mutex<Mixer>>, health: &Arc<StreamHealth>) -> Result<(Stream, i64), Error> {
    let device = cpal::default_host()
        .default_output_device()
        .ok_or_else(|| Error::new(ErrorKind::DeviceNotAvailable))?;
    let supported = device.default_output_config()?;
    let config = supported.config();
    let (encoding, stream) = match supported.sample_format() {
        SampleFormat::F32 => (1, build::<f32>(&device, config, mixer, health)),
        SampleFormat::I16 => (2, build::<i16>(&device, config, mixer, health)),
        SampleFormat::I32 => (3, build::<i32>(&device, config, mixer, health)),
        SampleFormat::U16 => (4, build::<u16>(&device, config, mixer, health)),
        _ => return Err(Error::new(ErrorKind::UnsupportedConfig)),
    };
    let stream = stream?;
    stream.play()?;
    log::info!(
        "Sampler output opened at {} Hz, {} channels",
        config.sample_rate,
        config.channels
    );
    let negotiated =
        (i64::from(config.sample_rate) << 32) | (i64::from(config.channels) << 16) | encoding;
    Ok((stream, negotiated))
}

fn build<T: SizedSample + Sample + FromSample<f32> + Send + 'static>(
    device: &cpal::Device,
    config: StreamConfig,
    mixer: &Arc<Mutex<Mixer>>,
    health: &Arc<StreamHealth>,
) -> Result<Stream, Error> {
    let channels = usize::from(config.channels);
    let rate = config.sample_rate;
    if channels == 0 || rate == 0 {
        return Err(Error::new(ErrorKind::UnsupportedConfig));
    }
    mixer
        .lock()
        .map_err(|_| Error::new(ErrorKind::StreamInvalidated))?
        .prepare_output(rate, channels);
    let mixer = Arc::clone(mixer);
    let callbacks = Arc::clone(health);
    let errors = Arc::clone(health);
    let reserve_samples = (rate * RENDER_RESERVE_MS / 1000).max(1) as usize * channels;
    let mut reserve = VecDeque::with_capacity(reserve_samples);
    let mut scratch = vec![T::EQUILIBRIUM; reserve_samples];
    device.build_output_stream(
        config,
        move |output: &mut [T], _| {
            callbacks.heartbeat.store(true, Relaxed);
            let ready = output.len().min(reserve.len());
            for (destination, sample) in output[..ready].iter_mut().zip(reserve.drain(..ready)) {
                *destination = sample;
            }
            // Keep a few milliseconds of audio ready across short mixer lock conflicts.
            // Both buffers are allocated before the stream starts.
            if let Ok(mut mixer) = mixer.try_lock() {
                if ready < output.len() {
                    mixer.render(&mut output[ready..], channels, rate);
                }
                let refill = reserve_samples - reserve.len();
                mixer.render(&mut scratch[..refill], channels, rate);
                drop(mixer);
                reserve.extend(scratch[..refill].iter().copied());
            } else {
                output[ready..].fill(T::EQUILIBRIUM);
            }
        },
        move |error| {
            if !matches!(error.kind(), ErrorKind::Xrun | ErrorKind::RealtimeDenied) {
                errors.failed.store(true, Relaxed);
            }
        },
        None,
    )
}
