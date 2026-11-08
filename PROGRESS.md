# Progress

Status against the phases in `SPEC.md` §4.

## Current phase: Phase 2 — Camera (in progress)

Phase 0 was confirmed by the owner on 2026-11-07. Phase 1 (1a–1c) was confirmed by the owner on 2026-11-08. Milestone 1a was tested by the owner on 2026-11-07 (alignment OK; three issues reported, fixed in 1b below).

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
Split into milestones (owner's choice): **1a** engine + recording + playback + minimal projects; **1b** metronome + count-in + manual offset; **1c** device profiles + 3 calibration methods + leak detection + automatic alignment with calibration.

### 1a — Done (tested by owner)
- `:core:model`: `Project`/`Track`/`Take` (schema v1, kotlinx.serialization), `AlignmentInfo`, `AlignmentMath` (timestamp-based offset `D = ioOffset + I_s - O_s - residual`, estimate fallback).
- `:core:project`: `ProjectRepository` with atomic `project.json` writes (temp + fsync + rename), schema migration hook, crash recovery of interrupted takes (WAV header repaired from file size).
- `:core:audio` native `AudioEngine`: full-duplex (output callback drives the clock; input drained non-blocking each callback), lock-free SPSC rings, N-track playback streamed from disk by a reader thread (2 s pre-buffer per track, aligned to the project timeline), click-free gain ramps, WAV float32 recording via a writer thread (header refreshed every second), ioOffset measured continuously from both streams' timestamps (median of 32).
- Kotlin `AudioEngine` API, `AudioRouting` (preferred input by detected category: USB > wired headset mic > built-in; stereo for external 2-channel inputs), `TakeAlignment`.
- `:feature:projects`: list, create (native sample rate), delete. `:feature:recorder`: input meter(s) + channel 1/2/stereo selection, play/stop, record new track, per-track on/off and delete, free space + remaining time, engine info and xrun/underrun counters, keeps screen on and locks rotation while recording. Device change or stream loss: take saved, engine reopened on the new route.
- Unit tests: alignment math, project serialization, store/recovery (12 tests).
- Verified on the phone (built-in mic + speaker): full-duplex AAudio MMAP both directions, 0 xruns/underruns/dropped; 3 takes got automatic offsets of −649/−646/−632 frames (−13.5/−13.5/−13.2 ms; Oboe estimates sum ≈ 13.5–14 ms); playback auto-stops at the end; app to background while recording → take saved; process killed while recording → take recovered on relaunch.

### 1a checklist (owner)
1. Create a project, record track 1 (e.g. clapping or playing a few beats), then record tracks 2–4 **with headphones**, playing along with what you hear.
2. Play back: tracks should sound roughly aligned (residual latency is only compensated in 1c, so a small constant lag may remain, especially over Bluetooth).
3. Toggle "Som" on a track during playback: no clicks.
4. With the USB interface: it should be picked as input automatically; with 2 channels, try Canal 1 / Canal 2 / Estéreo.
5. Plug/unplug the interface while recording: the take is kept, a message appears, the engine restarts.
6. Press Home while recording, come back: the take is there.
7. Long take (a few minutes) with several tracks playing: xruns/underruns/dropped stay at 0.

### 1a known issues / notes
- A take recovered after a crash has no automatic offset yet (0 ms), because the alignment is computed when the take is stopped. 1c will fall back to the device profile's last round trip.
- ioOffset samples have ~2 ms spread (timestamp granularity); the median varies by ~0.35 ms between takes. Within ±1 ms, but 1c will verify against the loopback measurement and may need a longer window.
- Track renaming and takes per track (re-record) come later (takes UI is in Phase 2 per SPEC); playback always starts at 0 (seek comes with the mixer/timeline).
- WAV files over 4 GB would need RF64 (≈ 3 h stereo float at 48 kHz); not supported yet.
- ~~"mono/stereo" not translated~~ (fixed in 1b). The alignment method name ("timestamps"/"estimate") in the track row is still not translated.

### Fixes from the owner's 1a test (done in 1b)
- **App closed when the USB interface was plugged/unplugged.** Cause: `fillStatus()` queried MMAP/API state on a stream the system had already closed (SIGSEGV in `AAudioStream_isMMapUsed`). Fix: MMAP/API cached at open; the Oboe error callback marks the stream disconnected; every status/timestamp/stop path checks the stream is still usable; the callback skips a disconnected input. Same guard in the diagnostics probe.
- **Track volume low, no way to raise it.** Per-track volume fader (−∞…+24 dB, saved in `project.json`, live while dragging, tap the value to reset to 0 dB). This brings part of SPEC Phase 4 forward. A soft clip above −0.9 dBFS on the master protects the output until the Phase 4 limiter.
- **Other tracks faintly audible when only one plays.** The engine applies gain 0 to tracks that are off, so the most likely cause is acoustic bleed: the headphone sound reaching the mic while recording the later tracks (it is inside the recordings). Each track now has its own output level meter: if an "off" track's meter is empty but you still hear it, it is bleed in the other takes. Leak detection/reduction is planned for 1c (SPEC §2.2).

### 1b — Done (confirmed by owner on 2026-11-08)
- `:core:model`: `MetronomeSettings` per project (enabled, BPM 30–300, beats per bar + beat unit, accent, count-in 0–4 bars, sound, volume, visual flash), tap tempo math, `Levels` (dB ↔ linear), `Track.volumeDb`/`playbackGain`. Old `project.json` files load with defaults.
- Native engine: click synthesis at open (3 sounds × normal/accent, preallocated), sample-accurate clicks mixed into the **output only** after the tracks (never recorded, never exported); beat 0 = project frame 0. Count-in: recording starts at project frame −countIn, so the take begins during the count-in clicks; the take is placed on the timeline with that negative start, so the count-in part is simply before 0. Stopping during the count-in discards the take.
- Per-track post-gain peak meters.
- Recorder UI: metronome card (on/off, −/+ and slider for BPM, Tap, 2/4 3/4 4/4 6/8 + custom signature, count-in, sound, click volume, accent, visual flash), negative time and "Contagem n/N" during count-in, visual flash dot (accent beat in red), per-track fader + meter, manual offset dialog (ms, ±1/±10 nudges, reset; idle only).
- Unit tests: 17 (new: metronome/tap tempo/dB).
- Smoke-tested on the phone (built-in mic, phone muted): count-in shows −0:01.xxx and "Contagem 2/4", take saved with automatic offset −13.3 ms, playback with the per-track meter moving, manual offset +10 ms and fader +19.7 dB saved to `project.json`, no crashes. The clicks themselves were not listened to (phone muted), and the USB plug/unplug fix was not exercised (interface not connected).

### 1b checklist (owner)
1. **USB fix:** with the recorder open, plug and unplug the USB interface a few times (idle, playing, and recording). The app must not close; while recording, the take is kept and a message appears.
2. **Volume:** with a few tracks, move each track's fader during playback. It changes smoothly with no clicks; +24 dB is clearly louder; loud passages are softly limited instead of crackling. Leave and reopen the project: the fader positions are kept.
3. **Bleed:** turn off all tracks except one and play. Look at the meters of the "off" tracks: they must stay empty. If you still hear the other parts faintly, it is in the recording (headphone bleed). Try recording a new take at a lower headphone volume, or with closed-back headphones, and compare.
4. **Metronome:** turn it on and press Play. The clicks play in the headphones in time; the first beat is accented (when on). Try 3/4, 6/8, a custom signature (e.g. 5/4 or 7/8), the 3 sounds, and the volume. Change the BPM with −/+, the slider, and Tap (tap 4+ times).
5. **Count-in:** set 1, 2, 4 bars and record. You hear N bars of clicks with a negative time and "Contagem n/N" before 0:00. Set "Desligada": recording starts immediately. Count-in plays even with the metronome off.
6. **The click is not recorded:** record a take with the metronome on, then turn the metronome off and play that take. You must not hear clicks (except acoustic bleed from the headphones, as in item 3).
7. **Visual flash:** turn it on. The dot next to the time flashes on each beat (red on the accented beat).
8. **Manual offset:** on a track, tap the pencil, add e.g. +50 ms, OK. On playback that track is noticeably later; reset it to 0.
9. Record a stereo take on the USB interface with the metronome on: no xruns/dropped.

### 1b known issues / notes
- The visual flash follows the 50 ms status refresh, so it can be up to ~50 ms off and a little early relative to the click you hear (output latency). It is a visual aid only.
- Changing the BPM during playback moves the click grid immediately (the grid is always anchored at project frame 0).
- The take length shown in the track row includes the count-in.
- After Stop, the time shows the last position (playback always starts at 0 until the timeline/seek arrives).

### 1c — Done (confirmed by owner on 2026-11-08)
- **Device profiles** (`:core:model` `DeviceProfile`, `:core:project` `DeviceProfileRepository` → `files/device_profiles.json`, atomic writes). A profile belongs to a **route** (output device + input device), because the round trip depends on both ends (e.g. built-in mic + wired headphones ≠ built-in mic + speaker); for a USB interface both ends are the same device. The key is type + product name + address, used only to find the saved data; behavior comes only from what is stored (measured or chosen). Stored: calibration, leak test, input channel, digital input gain, software monitoring on/off + level, last sample rate. Loaded automatically when the engine opens on a route (also after plug/unplug); the snackbar says whether the new route is calibrated.
- **Calibration, 3 methods** (`:core:audio` `LatencyCalibrator` + pure-JVM `CalibrationDsp`). All run through the real engine path: the output plays a known signal at known project positions while recording a normal take; the take is aligned with the stream timestamps as usual, and what is still off is the **residual** (converters, analog path, Bluetooth codec…). New takes on that route use `D = timestamps offset − residual` (SPEC 3.4); the take stores the profile key and method.
  - Acoustic and loopback: 12 Hann-windowed 10 ms chirps (500 Hz–8 kHz) at 80 BPM; one common lag by normalized cross-correlation over −100…+600 ms, then each impulse refined with sub-sample interpolation; polarity by vote; median and spread of the accepted impulses (outliers dropped by MAD). Fails with a clear message if the signal is not found (coherence < 0.2) or the impulses disagree.
  - Tap: 4 listening clicks + 16 to play along with; attack detection per beat, median with outlier rejection (≥ 6 taps needed).
- **Playback-leak test**: the same impulses with the instrument silent; "leak" if they come back coherently (normalized correlation ≥ 0.12 and most impulses agree). Level (dB relative to what was played) and delay are saved to the profile; the recorder shows a warning on that route.
- **Software monitoring** (native, ramped, never recorded or exported): off by default; not allowed when the output is a loudspeaker (built-in speaker/earpiece, BLE speaker, HDMI…); shows the estimated monitoring latency. **Digital input gain** (−24…+24 dB, ramped) applied before metering, monitoring and recording.
- Recorder UI: device card (output/input, calibration status, leak warning, Bluetooth latency warning, input meters/channel, input gain, monitoring), calibration dialog (method choice, progress, cancel, result with round trip/residual/spread/accepted count, Save/Discard/Try again). Diagnostics screen: "Device profiles" section with calibrated latency, leak, input settings, delete.
- Unit tests: 27 (new: chirp detection incl. weak/negative-lag/inverted/jittered signals, silence ≠ leak, tap averaging with outliers, robust stats, profile store round trip/corrupt file).
- On the phone: installed and launches without crash (profiles load). **Not smoke-tested on the device**: the phone was locked when the build was ready (I don't enter the PIN). Calibration, leak test and monitoring have only been verified by the unit tests so far.

### 1c checklist (owner)
1. **Loopback (acceptance test, SPEC 3.4 a):** USB interface, cable from its output to its input. Recorder → "Calibrar / testar" → "Cabo de loopback". Expect round trip and spread ≤ 1 ms; Save. Then, with the metronome on, record 5 tracks with the cable still connected (each track records the clicks + previous tracks coming back). Play them: the clicks must sound as one (no flam). Optional: send me the project and I check the transients numerically.
2. **Acoustic (built-in mic):** no headphones, media volume ~70 %, quiet room → "Acústico". Repeat 2–3 times: the round trip should repeat within ~1 ms. Save.
3. **Tap:** with headphones → "Tocar junto": listen to 4 clicks, then tap/strum along with 16. Result within a few ms of the acoustic/loopback one (it is less precise by nature).
4. **Leak test:** USB interface, instrument silent, no cable → "Teste de vazamento". If your interface mixes the phone audio back into its input you get a warning with the level; otherwise "Sem vazamento". This is probably the cause of the "faint other tracks" from the 1a test, if it happened with the interface.
5. **Profiles follow the device:** calibrate the built-in route and the USB route. Plug/unplug the interface: the snackbar says which route is active and whether it is calibrated; the device card shows the right values. Settings → Diagnóstico de áudio → "Perfis de dispositivo" lists both; delete works.
6. **Input gain and channel:** change the input gain and channel 1/2/stereo on the interface; leave the project and come back, or unplug/replug: they are restored.
7. **Software monitoring:** on the speaker the switch is disabled. With headphones (or the interface with direct monitoring off), turn it on: you hear yourself with a small delay; the monitoring level slider works; turning it off is click-free. It is not in the recording.
8. **Phase 1 criterion (SPEC 4):** 4+ tracks in two scenarios (built-in mic with headphones, and USB interface), each route calibrated, sound perfectly aligned.

### 1c known issues / notes
- A take recovered after a crash still has no automatic offset (0 ms): the alignment is computed at stop. The profile's residual alone is not enough because the timestamp part is missing; fixing it needs the start counters persisted during recording (later).
- The tap method includes the player's own timing bias (anticipating or lagging the click), as the SPEC expects.
- The calibration measures at the project sample rate; the residual is stored in ms, so it applies to projects at other rates too.
- Sample rate choice per device is only recorded (last used), not applied: the engine always runs at the project rate (Oboe resamples if the device differs), per SPEC 3.3.
- Software monitoring latency is the output buffer plus up to two input blocks (a few ms on MMAP); with OpenSL ES or Bluetooth it is much higher.

