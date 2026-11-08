# Repository Guidelines

## Project Structure & Module Organization

- `app/src/main/java/work/stdpi/smolkey/` contains the Android app: Compose screens and surfaces, MIDI routing, and the Kotlin JNI wrapper for the sampler.
- `app/src/main/assets/instruments.smb` is the bundled sample bank. `app/src/test/` holds JVM tests; `app/src/androidTest/` holds device UI tests.
- `native/src/` contains the Rust `ensemble` library: bank decoding, voice mixing, Android JNI bindings, and audio output. `tools/build_sampler_bank.py` rebuilds the bank from the documented Splendid Grand Piano source.
- Root Gradle files configure the Android build; `native/Cargo.toml` configures the Rust library. Generated Gradle and Cargo outputs are ignored.

## Build, Test, and Development Commands

Use JDK 17+, Android SDK 37, NDK 25.2.9519653, CMake, Rust 1.85+, `cargo-ndk`, and Android Rust targets. The Gradle build compiles and packages the native library.

- `./gradlew assembleDebug` builds the debug APK.
- `./gradlew lintDebug testDebugUnitTest` runs Android lint and JVM tests.
- `./gradlew connectedDebugAndroidTest` runs Compose interaction tests on a connected device or emulator.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` installs the APK.
- `python3 tools/build_sampler_bank.py /path/to/SplendidGrandPiano` regenerates the bundled sampler bank; use the pinned source revision in `README.md`.

## Coding Style & Naming Conventions

Follow Kotlin official style, configured by `kotlin.code.style=official`. Keep Android UI and platform APIs in `app/`; keep decoding and real-time mixing in `native/`. Rust uses edition 2024; run `cargo fmt --manifest-path native/Cargo.toml -- --check` before submitting Rust changes. Keep JNI method names and signatures aligned between `NativeSampler.kt` and `native/src/lib.rs`.

## Testing Guidelines

Name JVM tests `*Test.kt` under `app/src/test/`. Use `testDebugUnitTest` for controller, MIDI, and mode logic. Use `connectedDebugAndroidTest` for touch, layout, and device behavior that depends on Android or Compose.

## Commit & Pull Request Guidelines

This repository has no commit subjects yet. Suggested commit convention: `feat:`, `fix:`, or `docs:` followed by a short imperative summary. Pull requests should describe behavior changes, list build and test commands run, link related issues, and include screenshots for visible UI changes.
