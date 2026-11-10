# Repository Guidelines

## Project Structure & Module Organization

- `app/src/main/java/work/stdpi/smolkey/` contains the Android app, including Compose screens and MIDI routing. `ensemble/src/main/java/work/stdpi/ensemble/` contains the Kotlin engine API, bank management, MIDI playback, and JNI bindings.
- `app/src/main/assets/instruments.smb` is the bundled sample bank. `app/src/test/` holds JVM tests; `app/src/androidTest/` holds device UI tests.
- `native/src/` contains the Rust `ensemble` library: bank decoding, voice mixing, Android JNI bindings, and audio output. `tools/build_sampler_bank.py` compiles arbitrary banks from manifests. `tools/build_smolkey_bank.py` prepares the bundled bank from the documented source.
- Root Gradle files configure the Android build; `native/Cargo.toml` configures the Rust library. Generated Gradle and Cargo outputs are ignored.

## Build, Test, and Development Commands

Use JDK 17+, Android SDK 37, NDK 25.2.9519653, CMake, Rust 1.85+, `cargo-ndk`, and Android Rust targets. The Gradle build compiles and packages the native library.

- `./gradlew assembleDebug` builds the debug APK.
- `./gradlew lintDebug testDebugUnitTest` runs Android lint and JVM tests.
- `./gradlew connectedDebugAndroidTest` runs Compose interaction tests on a connected device or emulator.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` installs the APK.
- `python3 tools/build_smolkey_bank.py /path/to/SplendidGrandPiano` regenerates the bundled sampler bank; use the pinned source revision and instructions in `docs/ensemble-bank.md`.

## Coding Style & Naming Conventions

Follow Kotlin official style, configured by `kotlin.code.style=official`. Keep the Android UI in `app/`, engine APIs in `ensemble/`, and decoding and real-time mixing in `native/`. Rust uses edition 2024; run `cargo fmt --manifest-path native/Cargo.toml -- --check` before submitting Rust changes. Keep JNI method names and signatures aligned between `Ensemble.kt` and `native/src/android.rs`.

## Testing Guidelines

Name JVM tests `*Test.kt` under `app/src/test/`. Use `testDebugUnitTest` for controller, MIDI, and mode logic. Use `connectedDebugAndroidTest` for touch, layout, and device behavior that depends on Android or Compose.

## Commit & Pull Request Guidelines

This repository has no commit subjects yet. Suggested commit convention: `feat:`, `fix:`, or `docs:` followed by a short imperative summary. Pull requests should describe behavior changes, list build and test commands run, link related issues, and include screenshots for visible UI changes.
