# Smolkey

Smolkey turns an Android phone into a playable piano and a USB or Bluetooth MIDI controller.

## Build

Use the pinned JDK 17, Android SDK platform 37, NDK 25.2.9519653, and Rust 1.85.1 toolchains. The Gradle wrapper pins Gradle 9.6.0 and verifies its SHA-256 checksum. Gradle dependency lockfiles and `native/Cargo.lock` pin resolved libraries. Set the local SDK path in `local.properties` if Gradle cannot find your SDK.

```sh
rustup toolchain install 1.85.1 --profile minimal --target aarch64-linux-android,armv7-linux-androideabi,i686-linux-android,x86_64-linux-android
cargo +1.85.1 install cargo-ndk --version 3.5.4 --locked
./gradlew --no-daemon assembleDebug lintDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n work.stdpi.smolkey/.MainActivity
```

GitHub Actions runs the same Android build, lint, and JVM checks for pushes and pull requests, then stores the debug APK as a workflow artifact. Android SDK platform and NDK packages are installed at the versions configured by the project.

## Local audio

`MidiSamplerConsumer` forwards local instrument events to Ensemble (`libensemble.so`). The Rust mixer plays up to 128 voices through CPAL/AAudio. It mixes stereo PCM with short attack and release ramps, pitch interpolation, and a stereo-linked output limiter with 2.7 ms of lookahead at 48 kHz. When all slots are occupied, it favors held notes and loops over quieter decaying voices and fades the voice it replaces. The output thread reopens the default device on a stream error or stalled callback and resumes from the current voice positions.

**Room reverb** in Settings adjusts the send to a fixed, dark stereo room from 0% to 100%. The direct sound stays present at every setting. The app remembers the amount, which defaults to 0%.

`NativeSampler.loadStem` takes mono or stereo Ogg Opus and optional caller-defined tags through `Metadata`. Load on a worker thread. Each `fire` returns an independently controllable voice, with gain, playback rate, and optional looping. Stems stay loaded until the sampler closes. The decoded bank and stems share a 128 MiB PCM budget.

The piano bank is derived from the public-domain Akai samples and note maps in [Splendid Grand Piano](https://github.com/sfzinstruments/SplendidGrandPiano/tree/1c595d827b7fd7f0be3134aaed0f90b3662a09cf). The bank keeps the original key zones and all four stereo velocity layers, sharing recordings where the source does. Tails are capped at 6.5 seconds in the bass, 4.5 seconds in the middle, and 3 seconds in the treble. Bass and midrange PCM use 24 kHz and treble uses 48 kHz. The mixer applies note-dependent damper release, lets undamped high notes ring, and softens very light strikes. The drum and tambourine clips are gain-matched on a 20 ms RMS window with peak headroom, then the mixer applies MIDI velocity to each hit or roll.

The APK ships one checked-in `instruments.smb` bank. The native loader streams clips from the Android asset and decodes batches with up to four workers. SMB3 uses a plain-text playback header, then text clip headers followed by binary Ogg Opus payloads. Each `clip` line gives the decode rate in Hz and the Opus byte count. Each `zone` line gives the inclusive low and high keys, root key, velocity layer, release in milliseconds (zero for undamped), and gain in hundredths of a dB. A blank line ends each header. To regenerate the bank, check out Splendid Grand Piano at commit `1c595d827b7fd7f0be3134aaed0f90b3662a09cf`, use Python 3 and FFmpeg, then run `python3 tools/build_sampler_bank.py /path/to/SplendidGrandPiano`. The percussion source clips and legacy piano recordings are kept in `tools/`; Android and Rust build outputs stay ignored.

## Native benchmarks

Run the Criterion suite on the host with `cargo bench --locked --manifest-path native/Cargo.toml --bench sampler`. It measures 128-frame stereo blocks at 48 kHz. The `effects_128_frames` group isolates reverb at 35% and the limiter below and above its ceiling. The `render_128_frames` group mixes 0, 16, or 128 looping piano stems, with reverb off and at 35% for the 16-voice case. To run only the effects group, append `-- effects_128_frames`. The HTML report is at `native/target/criterion/report/index.html`.

## USB MIDI

Connect the phone's USB-C port to a computer's USB host port with a data cable. In the Android USB notification, set **Use USB for** to **MIDI**. Select **USB** in Smolkey. Android and phone-manufacturer support for USB peripheral MIDI varies.

On macOS, open Audio MIDI Setup and verify the Android USB MIDI endpoint. On Windows, select the Android MIDI endpoint in your MIDI monitor or DAW. On Linux, select the Android ALSA MIDI endpoint.

## Bluetooth MIDI

Select **Bluetooth**, grant **Nearby devices**, then tap **Start Bluetooth**. Connect to Smolkey from the host's Bluetooth MIDI settings and keep Smolkey in the foreground while playing.

- macOS: open Audio MIDI Setup, choose **Window → Show MIDI Studio**, and connect the Smolkey device in Bluetooth Configuration.
- Windows: install and open the Windows MIDI Services Bluetooth Setup tool, then connect Smolkey under **Bluetooth devices**.
- Linux: use a BlueZ build with its MIDI plug-in enabled, or a BLE MIDI tool supported by your distribution. Some Linux setups need PipeWire or explicit BlueZ MIDI configuration.

The app UI and touch instrumentation have been tested on a Samsung Galaxy A73 5G (SM-A736B, Android 16). BLE MIDI was verified from that phone to Linux BlueZ using `bluetoothctl` GATT notification subscription. USB MIDI has not been verified with actual host traffic; peripheral support depends on the phone's Android USB MIDI implementation and system configuration.
