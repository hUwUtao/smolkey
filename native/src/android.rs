use std::collections::HashMap;
use std::ptr::{self, NonNull};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, OnceLock};
use std::time::Instant;

use jni::JNIEnv;
use jni::objects::{GlobalRef, JByteArray, JObject};
use jni::sys::{jboolean, jfloat, jint, jintArray, jlong};
use ndk::asset::AssetManager;

use crate::bank::{self, MAX_ENCODED_BYTES, RATE};
use crate::mixer::Mixer;
use crate::output::Output;

struct EngineEntry {
    mixer: Arc<Mutex<Mixer>>,
    bank_loaded: OnceLock<[i32; bank::DRUMS]>,
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
    if env.get_array_length(&bytes).ok()? as usize > MAX_ENCODED_BYTES {
        return None;
    }
    env.convert_byte_array(bytes).ok()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_open(
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

    let mixer = Arc::new(Mutex::new(Mixer::new()));
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
                bank_loaded: OnceLock::new(),
            }),
            output,
        ),
    );
    handle
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_loadBank(
    env: JNIEnv,
    _: JObject,
    handle: jlong,
    assets: JObject,
) -> jintArray {
    let Some(engine) = engine(handle) else {
        return ptr::null_mut();
    };
    // The JNI argument keeps the Java AssetManager alive until the asset is closed.
    let Some(manager) = NonNull::new(unsafe {
        ndk_sys::AAssetManager_fromJava(env.get_raw().cast(), assets.as_raw().cast())
    }) else {
        return ptr::null_mut();
    };
    let manager = unsafe { AssetManager::from_ptr(manager) };
    let Some(asset) = manager.open(c"instruments.smb") else {
        log::warn!("Could not open the bundled sampler bank");
        return ptr::null_mut();
    };
    if asset.length() > MAX_ENCODED_BYTES {
        log::warn!("Bundled sampler bank exceeds the encoded size limit");
        return ptr::null_mut();
    }
    let drums = engine.bank_loaded.get_or_init(|| {
        let start = Instant::now();
        let mut zones = Vec::new();
        let mut decoded = 0;
        let mut loaded = 0;
        let Some(playback) = bank::decode_bank(asset, |clip| {
            decoded += 1;
            let result = match engine.mixer.lock() {
                Ok(mut mixer) => mixer.load(clip.sample),
                Err(_) => return,
            };
            if let Ok(stem) = result {
                zones.extend(clip.zones.into_iter().map(|zone| (zone, stem)));
                loaded += 1;
            }
        }) else {
            log::warn!("Invalid sampler bank header");
            return [-1; bank::DRUMS];
        };
        let drums = match engine.mixer.lock() {
            Ok(mut mixer) => mixer.install(playback, zones),
            Err(_) => return [-1; bank::DRUMS],
        };
        log::info!(
            "Loaded {} bank clips in {} ms",
            loaded,
            start.elapsed().as_millis()
        );
        if decoded == 0 {
            log::warn!("No valid clips in the sampler bank");
        }
        drums
    });
    let Ok(array) = env.new_int_array(drums.len() as jint) else {
        return ptr::null_mut();
    };
    if env.set_int_array_region(&array, 0, drums).is_err() {
        return ptr::null_mut();
    }
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_loadStem(
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
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_fire(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    stem: jint,
    gain: jfloat,
    rate: jfloat,
    looping: jboolean,
) -> jlong {
    if stem < 0 || !gain.is_finite() || !rate.is_finite() {
        return 0;
    }
    with_mixer(handle, |mixer| {
        mixer.fire(
            stem as usize,
            gain.clamp(0.0, 2.0),
            f64::from(rate.clamp(0.25, 4.0)),
            looping != 0,
        )
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_percussion(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    stem: jint,
    velocity: jint,
    looping: jboolean,
    current: jlong,
) -> jlong {
    if stem < 0 || !(0..=127).contains(&velocity) {
        return 0;
    }
    with_mixer(handle, |mixer| {
        mixer.percussion(stem as usize, velocity as u8, looping != 0, current)
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_note(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    channel: jint,
    note: jint,
    velocity: jint,
) {
    if (1..=16).contains(&channel) && (0..=127).contains(&note) && (0..=127).contains(&velocity) {
        with_mixer(handle, |mixer| {
            mixer.note(((channel - 1) * 128 + note) as usize, velocity as u8)
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_gain(
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
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_stop(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    voice: jlong,
) {
    with_mixer(handle, |mixer| mixer.stop(voice));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_release(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
) {
    with_mixer(handle, Mixer::release);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_setReverb(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    percent: jint,
) {
    with_mixer(handle, |mixer| mixer.set_reverb(percent));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_setActive(
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
pub extern "system" fn Java_work_stdpi_smolkey_EnsembleBindings_close(
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
