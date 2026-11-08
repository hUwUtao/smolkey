use std::sync::atomic::{
    AtomicBool, AtomicU8,
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
const FIRST_RETRY: Duration = Duration::from_millis(100);

pub struct Output {
    state: Arc<AtomicU8>,
    thread: JoinHandle<()>,
}

impl Output {
    pub fn start(mixer: Arc<Mutex<Mixer>>) -> std::io::Result<Self> {
        let state = Arc::new(AtomicU8::new(ACTIVE));
        let worker_state = Arc::clone(&state);
        let thread = thread::Builder::new()
            .name("SmolkeyOutput".into())
            .spawn(move || supervise(mixer, worker_state))?;
        Ok(Self { state, thread })
    }

    pub fn set_active(&self, active: bool) {
        self.state
            .store(if active { ACTIVE } else { SUSPENDED }, Release);
        self.thread.thread().unpark();
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

fn supervise(mixer: Arc<Mutex<Mixer>>, state: Arc<AtomicU8>) {
    let mut retry = FIRST_RETRY;
    loop {
        match state.load(Acquire) {
            STOPPED => return,
            SUSPENDED => {
                retry = FIRST_RETRY;
                thread::park();
                continue;
            }
            _ => {}
        }
        let health = Arc::new(StreamHealth::default());
        match open(&mixer, &health) {
            Ok(stream) => {
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
        if state.load(Acquire) == ACTIVE {
            thread::park_timeout(retry);
            retry = (retry * 2).min(Duration::from_secs(2));
        }
    }
}

fn open(mixer: &Arc<Mutex<Mixer>>, health: &Arc<StreamHealth>) -> Result<Stream, Error> {
    let device = cpal::default_host()
        .default_output_device()
        .ok_or_else(|| Error::new(ErrorKind::DeviceNotAvailable))?;
    let supported = device.default_output_config()?;
    let config = supported.config();
    let stream = match supported.sample_format() {
        SampleFormat::F32 => build::<f32>(&device, config, mixer, health),
        SampleFormat::I16 => build::<i16>(&device, config, mixer, health),
        SampleFormat::I32 => build::<i32>(&device, config, mixer, health),
        SampleFormat::U16 => build::<u16>(&device, config, mixer, health),
        _ => Err(Error::new(ErrorKind::UnsupportedConfig)),
    }?;
    stream.play()?;
    log::info!(
        "Sampler output opened at {} Hz, {} channels",
        config.sample_rate,
        config.channels
    );
    Ok(stream)
}

fn build<T: SizedSample + Sample + FromSample<f32>>(
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
    device.build_output_stream(
        config,
        move |output: &mut [T], _| {
            callbacks.heartbeat.store(true, Relaxed);
            // Control operations have a short critical section. The callback never waits on it.
            if let Ok(mut mixer) = mixer.try_lock() {
                mixer.render(output, channels, rate);
            } else {
                output.fill(T::EQUILIBRIUM);
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
