# Progress

Status against the phases in `SPEC.md` §4.

## Current phase: Phase 0 — Foundation (waiting for owner's device test)

## Setup
- Windows 11 dev machine; Android Studio 2026.2.2.8 (bundled JBR = OpenJDK 25), Android SDK 37, build-tools 36.
- NDK 30.0.16248370 and CMake 3.30.4 are auto-installed by AGP on first build.
- Test phone: Android 17 (API 37), arm64-v8a, connected via wireless adb.
- Decisions: package `io.github.higor0227.selfband`; placeholder visual identity (Material 3 + dynamic color, seed colors in `:core:ui` `Theme.kt`) until Phase 7.

## Phase 0 — Foundation
### Done
- Gradle 9.9 wrapper (checksum-verified), version catalog (`gradle/libs.versions.toml`), AGP 9.5.1 with built-in Kotlin 2.4.21, KSP, Hilt 2.60.1, Compose BOM 2026.09.
- `build-logic` convention plugins: `selfband.android.application|library|feature`, `selfband.compose`, `selfband.hilt`, `selfband.jvm.library`. The feature plugin **fails the build if a feature module depends on another feature** (SPEC §3.2).
- All modules from SPEC §3.2 created. Placeholders (no code yet): `:core:project`, `:core:camera`, `:core:video`, `:core:export`, `:feature:studio|recorder|mixer|layout|export`.
- `:core:model`: `AudioDevice`, `AudioDeviceType`/`AudioDeviceCategory` (generic, from platform type constants only), `AudioSystemInfo`, `LOG_TAG`.
- `:core:audio`: C++ engine via NDK/CMake + Oboe 1.11 (prefab). `AudioProbe` opens low-latency output (silence) and optional input streams; the callbacks only touch atomics (no alloc/locks/log/IO). Kotlin API: `AudioDeviceMonitor` (AudioManager + AudioDeviceCallback), `AudioProbe` (JNI). Hilt bindings.
- `:core:ui`: `SelfbandTheme`, `SectionHeader`, `KeyValueRow`, `LevelMeter`.
- `:feature:settings`: audio diagnostics screen — system properties, device lists (inputs/outputs with rates/channels), live engine test (API, MMAP, sharing/performance mode, sample rate, burst, buffer, latency estimate, xruns, callbacks, input level). Reopens streams automatically after a route change.
- `:feature:projects`: empty project list with entry to diagnostics.
- UI strings in en + pt-BR; per-app language support (generated locale config).
- GitHub Actions workflow (`.github/workflows/android.yml`): unit tests + debug APK artifact.
- README (en), LICENSE (GPL-3.0 official text), CONTRIBUTING, CLAUDE.md.
- Verified on the phone: built-in speaker output = AAudio, MMAP, exclusive, 48 kHz, burst 96, buffer 192 frames (4 ms), latency estimate ~9 ms, 0 xruns. Built-in mic input = AAudio, MMAP, exclusive, 48 kHz mono, 0 xruns, level meter working. `assembleRelease` (R8) builds.

### Phase 0 checklist (owner)
1. App opens without crash; "Projetos" screen → gear icon → "Diagnóstico de áudio".
2. "Sistema" section and the input/output lists look plausible.
3. "Iniciar teste": output stream opens; leave it running ~1 min, xruns stay at 0 (or low).
4. Enable "Incluir entrada": the permission dialog appears; after allowing, the level meter reacts to your voice.
5. With the test running, plug in the USB audio interface: it appears in the lists, "Streams reabertos" increments, and the streams move to the USB device (check its sample rates/channels). Unplug: back to built-in, no crash.
6. Same with wired headphones and/or Bluetooth if available (Bluetooth: expect no MMAP and higher latency).
7. Switch the app language to English (Android settings → Apps → Selfband → Language) and check the UI.
8. Leaving the diagnostics screen stops the test (the mic privacy indicator disappears).

### Known issues / notes
- At startup the device callback logs "Audio device added" for devices already present; this is platform behavior (initial callback), harmless.
- The input stream's "Buffer" shows the input buffer size (e.g. 126 ms); for input this is headroom, not latency. The latency estimate row is what matters.
- CI workflow not verified yet (no GitHub remote).
- Debug APK is ~70 MB (3 ABIs, unstripped native libs, debug Compose). Release is much smaller.

## Phase 1 — Audio engine and sync
Not started.
