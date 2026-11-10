use std::collections::HashMap;
use std::fs::File;
use std::os::fd::BorrowedFd;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, OnceLock};
use std::time::Instant;

use jni::JNIEnv;
use jni::objects::{GlobalRef, JByteArray, JIntArray, JObject};
use jni::sys::{jboolean, jfloat, jint, jintArray, jlong};

use crate::bank::{self, MAX_ENCODED_CLIP_BYTES, RATE};
use crate::fx::{EffectControls, EffectKind};
use crate::mixer::Mixer;
use crate::output::Output;
use crate::streaming;

struct EngineEntry {
    mixer: Arc<Mutex<Mixer>>,
    effects: Arc<EffectControls>,
    bank_loaded: Mutex<bool>,
}

static CONTEXT: OnceLock<GlobalRef> = OnceLock::new();
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ENGINES: LazyLock<Mutex<HashMap<i64, (Arc<EngineEntry>, Output)>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn engine(handle: jlong) -> Option<Arc<EngineEntry>> {
    ENGINES
        .lock()
        .ok()?
        .get(&handle)
        .map(|(entry, _)| Arc::clone(entry))
}

fn with_mixer<T>(handle: jlong, action: impl FnOnce(&mut Mixer) -> T) -> Option<T> {
    let engine = engine(handle)?;
    let mut mixer = engine.mixer.lock().ok()?;
    Some(action(&mut mixer))
}

fn encoded(env: &JNIEnv, bytes: JByteArray) -> Option<Vec<u8>> {
    if env.get_array_length(&bytes).ok()? as usize > MAX_ENCODED_CLIP_BYTES {
        return None;
    }
    env.convert_byte_array(bytes).ok()
}

const API_VERSION: jint = 4;

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_negotiate(
    _: JNIEnv,
    _: JObject,
    minimum: jint,
    maximum: jint,
) -> jint {
    if (minimum..=maximum).contains(&API_VERSION) {
        API_VERSION
    } else {
        -API_VERSION
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_outputFormat(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
) -> jlong {
    ENGINES
        .lock()
        .ok()
        .and_then(|engines| engines.get(&handle).map(|(_, output)| output.format()))
        .unwrap_or(-3)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_open(
    env: JNIEnv,
    _: JObject,
    context: JObject,
) -> jlong {
    let (Ok(vm), Ok(context)) = (env.get_java_vm(), env.new_global_ref(context)) else {
        return 0;
    };
    // CPAL's AAudio backend looks up Android services through this process-wide context.
    CONTEXT.get_or_init(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("Ensemble")
                .with_max_level(log::LevelFilter::Info),
        );
        unsafe {
            ndk_context::initialize_android_context(
                vm.get_java_vm_pointer().cast(),
                context.as_obj().as_raw().cast(),
            );
        }
        context
    });

    let mixer = Mixer::new();
    let effects = mixer.effects.controls();
    let mixer = Arc::new(Mutex::new(mixer));
    let Ok(mut engines) = ENGINES.lock() else {
        return 0;
    };
    let output = match Output::start(Arc::clone(&mixer)) {
        Ok(output) => output,
        Err(error) => {
            log::error!("Could not start sampler output supervisor: {error}");
            return 0;
        }
    };
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    engines.insert(
        handle,
        (
            Arc::new(EngineEntry {
                mixer,
                effects,
                bank_loaded: Mutex::new(false),
            }),
            output,
        ),
    );
    handle
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_inspectBank(
    env: JNIEnv,
    _: JObject,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jintArray {
    let result = (|| {
        let (_, index) = read_bank_source(fd, offset, length)?;
        program_array(&env, &bank_metadata(&index))
    })();
    result.unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_loadBank(
    env: JNIEnv,
    _: JObject,
    handle: jlong,
    fd: jint,
    offset: jlong,
    length: jlong,
    required: JIntArray,
) -> jintArray {
    let result = (|| {
        let required = required_programs(&env, &required)?;
        let (file, index) = read_bank_source(fd, offset, length)?;
        install_bank(&env, handle, file, index, required)
    })();
    result.unwrap_or(std::ptr::null_mut())
}

fn read_bank_source(fd: jint, offset: jlong, length: jlong) -> Option<(Arc<File>, bank::Bank)> {
    if fd < 0 {
        return None;
    }
    // The Kotlin bank manager keeps the descriptor open for this entire JNI call.
    // The decoder owns a duplicate and uses positional reads.
    let file = Arc::new(File::from(
        unsafe { BorrowedFd::borrow_raw(fd) }
            .try_clone_to_owned()
            .ok()?,
    ));
    let metadata = file.metadata().ok()?;
    if !metadata.is_file() {
        return None;
    }
    let offset = u64::try_from(offset).ok()?;
    let available = metadata.len().checked_sub(offset)?;
    let length = if length == -1 {
        available
    } else {
        u64::try_from(length).ok()?
    };
    if length > available {
        return None;
    }
    let index = bank::Bank::read_file(Arc::clone(&file), offset, length)?;
    Some((file, index))
}

fn bank_metadata(index: &bank::Bank) -> Vec<jint> {
    let mut values = vec![index.clips.len() as jint];
    values.extend(
        index
            .programs
            .iter()
            .filter(|(_, program)| !program.zones.is_empty())
            .map(|(&id, _)| jint::from(id)),
    );
    values[1..].sort_unstable();
    values
}

fn required_programs(env: &JNIEnv, array: &JIntArray) -> Option<Vec<jint>> {
    let mut values = vec![0; usize::try_from(env.get_array_length(array).ok()?).ok()?];
    env.get_int_array_region(array, 0, &mut values).ok()?;
    Some(values)
}

fn program_array(env: &JNIEnv, values: &[jint]) -> Option<jintArray> {
    let array = env.new_int_array(values.len() as jint).ok()?;
    env.set_int_array_region(&array, 0, values).ok()?;
    Some(array.into_raw())
}

fn install_bank(
    env: &JNIEnv,
    handle: jlong,
    file: Arc<File>,
    index: bank::Bank,
    required: Vec<jint>,
) -> Option<jintArray> {
    let engine = engine(handle)?;
    let mut loaded = engine.bank_loaded.lock().ok()?;
    if *loaded {
        return None;
    }
    let start = Instant::now();
    let metadata = bank_metadata(&index);
    let programs = &metadata[1..];
    let mut missing = vec![-2];
    missing.extend(
        required
            .into_iter()
            .filter(|id| programs.binary_search(id).is_err()),
    );
    if missing.len() > 1 {
        return program_array(env, &missing);
    }
    let result = program_array(env, &metadata)?;
    let count = index.clips.len();
    streaming::install(&engine.mixer, file, index)
        .map_err(|error| log::warn!("Could not start bank decoder: {error}"))
        .ok()?;
    *loaded = true;
    log::info!(
        "Indexed {count} clips across {} programs in {} ms",
        programs.len(),
        start.elapsed().as_millis()
    );
    Some(result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_loadClip(
    env: JNIEnv,
    _: JObject,
    handle: jlong,
    bytes: JByteArray,
) -> jint {
    let Some(engine) = engine(handle) else {
        return -1;
    };
    let Some(bytes) = encoded(&env, bytes) else {
        return -1;
    };
    let Some(sample) = bank::decode(&bytes, 300 * RATE as usize) else {
        return -1;
    };
    let loaded = match engine.mixer.lock() {
        Ok(mut mixer) => mixer.load(sample),
        Err(_) => return -1,
    };
    loaded.map_or(-1, |id| id as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_fire(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    clip: jint,
    gain: jfloat,
    rate: jfloat,
    looping: jboolean,
) -> jlong {
    if clip < 0 || !gain.is_finite() || !rate.is_finite() {
        return 0;
    }
    with_mixer(handle, |mixer| {
        mixer.fire(
            clip as usize,
            gain.clamp(0.0, 2.0),
            f64::from(rate.clamp(0.25, 4.0)),
            looping != 0,
        )
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_pad(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    program: jint,
    note: jint,
    velocity: jint,
    looping: jboolean,
    current: jlong,
) -> jlong {
    let Ok(program) = u16::try_from(program) else {
        return 0;
    };
    if !(0..=127).contains(&note) || !(0..=127).contains(&velocity) {
        return 0;
    }
    with_mixer(handle, |mixer| {
        mixer.pad(program, note as u8, velocity as u8, looping != 0, current)
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_note(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    program: jint,
    channel: jint,
    note: jint,
    velocity: jint,
) {
    let Ok(program) = u16::try_from(program) else {
        return;
    };
    if (1..=16).contains(&channel) && (0..=127).contains(&note) && (0..=127).contains(&velocity) {
        with_mixer(handle, |mixer| {
            mixer.note(
                program,
                ((channel - 1) * 128 + note) as usize,
                velocity as u8,
            )
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_setPedal(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    channel: jint,
    value: jint,
) {
    if (1..=16).contains(&channel) && (0..=127).contains(&value) {
        with_mixer(handle, |mixer| {
            mixer.set_pedal((channel - 1) as usize, value as u8)
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_channelMix(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    channel: jint,
    gain: jfloat,
    pan: jfloat,
    semitones: jfloat,
) {
    if (1..=16).contains(&channel) && gain.is_finite() && pan.is_finite() && semitones.is_finite() {
        with_mixer(handle, |mixer| {
            mixer.set_channel_mix(
                (channel - 1) as usize,
                gain.clamp(0.0, 1.0),
                pan.clamp(-1.0, 1.0),
                semitones.clamp(-24.0, 24.0),
            )
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_silenceChannel(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    channel: jint,
) {
    if (1..=16).contains(&channel) {
        with_mixer(handle, |mixer| {
            mixer.silence_channel((channel - 1) as usize)
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_gain(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    voice: jlong,
    gain: jfloat,
) -> jboolean {
    (gain.is_finite()
        && with_mixer(handle, |mixer| mixer.gain(voice, gain.clamp(0.0, 2.0))).unwrap_or(false))
        as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_stop(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    voice: jlong,
) {
    with_mixer(handle, |mixer| mixer.stop(voice));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_release(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
) {
    with_mixer(handle, Mixer::release);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_loadFx(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    kind: jint,
) -> jint {
    let Some(kind) = EffectKind::from_id(kind) else {
        return -1;
    };
    with_mixer(handle, |mixer| mixer.effects.load(kind))
        .flatten()
        .map_or(-1, |slot| slot as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_setFxFloat(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    slot: jint,
    parameter: jint,
    value: jfloat,
) -> jboolean {
    let (Ok(slot), Ok(parameter)) = (usize::try_from(slot), usize::try_from(parameter)) else {
        return 0;
    };
    engine(handle).is_some_and(|engine| engine.effects.set_float(slot, parameter, value))
        as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_setActive(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    active: jboolean,
) {
    let Ok(engines) = ENGINES.lock() else { return };
    if let Some((entry, output)) = engines.get(&handle) {
        output.set_active(active != 0);
        if active == 0 {
            if let Ok(mut mixer) = entry.mixer.lock() {
                mixer.clear();
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_ensemble_EnsembleBindings_close(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
) {
    let removed = ENGINES
        .lock()
        .ok()
        .and_then(|mut engines| engines.remove(&handle));
    if let Some((_, output)) = removed {
        output.close();
    }
}
