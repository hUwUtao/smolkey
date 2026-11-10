use std::fs::File;
use std::io::BufReader;
use std::sync::{Arc, Mutex, Weak};
use std::thread;

use smol::channel::{self, Receiver, Sender};
use smol::{Executor, future};

use crate::bank::{Bank, Clip, FileRegion, OpusStream, PcmWriter};
use crate::mixer::Mixer;

pub const QUEUE_CAPACITY: usize = 128;
pub const MAX_OPEN_DECODERS: usize = 8;
const MAX_WORKERS: usize = 2;
const FIRST_FRAMES: usize = 2048;
const MAX_CONSECUTIVE_STARTS: usize = 2;
const TAIL_FRAMES: usize = 4096;

struct DecodeSlot(Sender<()>);

impl Drop for DecodeSlot {
    fn drop(&mut self) {
        let _ = self.0.try_send(());
    }
}

struct Decode {
    id: usize,
    stream: OpusStream<BufReader<FileRegion>>,
    _slot: DecodeSlot,
    writer: PcmWriter,
}

enum Work {
    Start(usize, DecodeSlot),
    Resume(Decode),
}

struct DecodePool {
    mixer: Weak<Mutex<Mixer>>,
    source: Arc<File>,
    clips: Arc<[Clip]>,
    jobs: Receiver<usize>,
    tails: Receiver<Decode>,
    resume: Sender<Decode>,
    slots: Receiver<()>,
    release_slot: Sender<()>,
    // Serialize PCM accounting with frees, while keeping deallocation off the mixer lock.
    admission: Mutex<()>,
}

impl DecodePool {
    async fn next(&self, starts: usize) -> Option<Work> {
        if self.jobs.is_closed() {
            return None;
        }
        // Give playing clips a slice even during a continuous burst of new requests.
        if starts >= MAX_CONSECUTIVE_STARTS {
            if let Ok(tail) = self.tails.try_recv() {
                return Some(Work::Resume(tail));
            }
        }
        if self.slots.try_recv().is_err() {
            // With all slots held, pending tails outnumber the running workers.
            return self.tails.recv().await.ok().map(Work::Resume);
        }
        let slot = DecodeSlot(self.release_slot.clone());
        future::or(
            async { self.jobs.recv().await.ok().map(|id| Work::Start(id, slot)) },
            async { self.tails.recv().await.ok().map(Work::Resume) },
        )
        .await
    }

    fn start(&self, id: usize, slot: DecodeSlot) -> Option<Decode> {
        let clip = self.clips[id];
        {
            let _admission = self.admission.lock().ok()?;
            let mixer = self.mixer.upgrade()?;
            let (reserved, evicted) = mixer.lock().ok()?.reserve_decode(id);
            drop(evicted);
            if !reserved {
                return None;
            }
        }
        let Some(stream) = OpusStream::open(
            clip.stream(Arc::clone(&self.source)),
            clip.rate,
            clip.frames,
        ) else {
            self.finish(id, false);
            return None;
        };
        if stream.channels != clip.channels {
            self.finish(id, false);
            return None;
        }
        let writer = PcmWriter::new(clip);
        self.mixer
            .upgrade()?
            .lock()
            .ok()?
            .begin_decode(id, writer.reader());
        Some(Decode {
            id,
            stream,
            _slot: slot,
            writer,
        })
    }

    fn finish(&self, id: usize, valid: bool) {
        if let Ok(_admission) = self.admission.lock() {
            let discarded = self
                .mixer
                .upgrade()
                .and_then(|mixer| mixer.lock().ok()?.finish_decode(id, valid));
            drop(discarded);
        }
    }

    async fn run(&self) {
        let mut pcm = Vec::new();
        let mut starts = 0;
        while let Some(work) = self.next(starts).await {
            let (mut decode, target) = match work {
                Work::Start(id, slot) => {
                    starts = (starts + 1).min(MAX_CONSECUTIVE_STARTS);
                    let Some(decode) = self.start(id, slot) else {
                        continue;
                    };
                    (decode, FIRST_FRAMES)
                }
                Work::Resume(decode) => {
                    starts = 0;
                    (decode, TAIL_FRAMES)
                }
            };
            let id = decode.id;
            let result = decode
                .stream
                .read_chunk(target, &mut pcm)
                .and_then(|finished| {
                    decode.writer.append(&pcm)?;
                    Some(finished)
                });
            if result == Some(false) {
                // Each tail owns a slot, so there is room to requeue it.
                if let Err(error) = self.resume.try_send(decode) {
                    drop(error);
                    self.finish(id, false);
                }
            } else {
                // Release the writer reference before the cache can evict this clip.
                drop(decode);
                self.finish(id, result == Some(true));
            }
            // Hand control back to the executor after each bounded PCM slice.
            future::yield_now().await;
        }
    }
}

pub fn install(mixer: &Arc<Mutex<Mixer>>, source: Arc<File>, index: Bank) -> std::io::Result<()> {
    let (queue, jobs) = channel::bounded(QUEUE_CAPACITY);
    let (resume, tails) = channel::bounded(MAX_OPEN_DECODERS);
    let (release_slot, slots) = channel::bounded(MAX_OPEN_DECODERS);
    for _ in 0..MAX_OPEN_DECODERS {
        release_slot.try_send(()).unwrap();
    }
    let pool = Arc::new(DecodePool {
        mixer: Arc::downgrade(mixer),
        source,
        clips: Arc::clone(&index.clips),
        jobs,
        tails,
        resume,
        slots,
        release_slot,
        admission: Mutex::new(()),
    });

    let executor = Arc::new(Executor::new());
    let (done, shutdown) = channel::bounded::<()>(1);
    let workers = thread::available_parallelism().map_or(MAX_WORKERS, |n| n.get().min(MAX_WORKERS));
    // Smol schedules the decode tasks. Android schedules the executor threads.
    for i in 0..workers {
        let executor = Arc::clone(&executor);
        let shutdown = shutdown.clone();
        thread::Builder::new()
            .name(format!("EnsembleDecode{i}"))
            .spawn(move || {
                smol::block_on(executor.run(shutdown.recv())).ok();
            })?;
    }
    for _ in 0..workers {
        let pool = Arc::clone(&pool);
        let done = done.clone();
        executor
            .spawn(async move {
                pool.run().await;
                drop(done);
            })
            .detach();
    }
    mixer
        .lock()
        .map_err(|_| std::io::Error::other("Sampler unavailable"))?
        .install(index, queue);
    Ok(())
}
