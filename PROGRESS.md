# Progress

Status against the phases in `SPEC.md` §4.

## Current phase: Phase 6 — Scale and experience (6c done; 6d in progress)

Phase 0 was confirmed by the owner on 2026-11-07. Phase 1 (1a–1c) was confirmed by the owner on 2026-11-08. Phase 2 is split into 2a (camera + video sync), 2b (camera controls, grid, ghost) and 2c (takes UI, audio-only tracks); 2a was confirmed by the owner on 2026-11-08 (clap test: 0 frames off). 2b was confirmed by the owner on 2026-11-08. 2c was confirmed by the owner on 2026-11-08: **phase 2 is complete.** Phase 3 is split into 3a (GL compositor + live preview driven by the audio clock), 3b (formats 9:16 / 16:9 / 1:1 + layout templates), 3c (layout editor: layer editing, crop, guides/snap) and 3d (canvas background; own templates move to Phase 6 and global trim to Phase 4, per SPEC §4); 3a was confirmed by the owner on 2026-11-08. 3b was confirmed by the owner on 2026-11-08. 3c was confirmed by the owner on 2026-11-08. 3d was confirmed by the owner on 2026-11-08: **phase 3 is complete.** Phase 4 is split into 4a (volume, pan, mute/solo, meters, master volume + limiter), 4b (EQ per track + reverb send) and 4c (offset by dragging the waveform + global trim); the mixer lives in the recorder as a third panel mode, and `:feature:mixer` stays a placeholder until Phase 7. 4a was confirmed by the owner on 2026-11-08. 4b was confirmed by the owner on 2026-11-08. 4c was confirmed by the owner on 2026-11-08: **phase 4 is complete.** Phase 5 is split into 5a (audio: mix WAV + dry stems, export screen, foreground service, MediaStore, share), 5b (MP4 H.264 1080p30 + AAC with the preview compositor) and 5c (HEVC, 720p/4K, 60 fps, manual bitrate, decoder limits). 5a was confirmed by the owner on 2026-11-08. 5b was confirmed by the owner on 2026-11-08. 5c was confirmed by the owner on 2026-11-08: **phase 5 is complete (end of the MVP).** Phase 6 is split into 6a (preview proxies), 6b (multi-pass export + tests with 12–16 tracks), 6c (settings: defaults, theme, language, verbose log), 6d (first-run wizard) and 6e (user-saved layout templates); proxies come first because they are the biggest risk of the phase. 6a was confirmed by the owner on 2026-11-08. 6b was confirmed by the owner on 2026-11-08. On 2026-11-08 the owner said to proceed after 6c (no test results reported yet). Milestone 1a was tested by the owner on 2026-11-07 (alignment OK; three issues reported, fixed in 1b below).

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

## Phase 2 — Camera
**Decision (owner, 2026-11-08):** Camera2 + MediaCodec + MediaMuxer instead of CameraX (SPEC 3.1 allows the fallback). CameraX does not tell which sensor timestamp the first recorded frame has, and the clap test needs ±1 frame. The implementation sits behind the `VideoCamera` interface in `:core:camera`, so it can be swapped.

### 2a — Done (confirmed by owner on 2026-11-08: clap test 0 frames off)
- `:core:camera`: `Camera2VideoCamera` (Hilt singleton). It opens the camera by the facing the platform reports (front/back/external). Video size is 1080p or the best 16:9 size below it that the encoder accepts; AE runs at 30 fps fixed when available, with continuous-video AF. One capture session holds the preview surface and a **persistent encoder surface**; the repeating request targets the encoder only while recording, so starting a take does not rebuild the session. Camera operations are serialized with a mutex.
- `VideoEncoder`: H.264 at 16 Mbit/s, keyframe every 1 s, MP4 with the rotation in the header (no audio track; the take's audio is the WAV). One codec per take; it is re-prepared after each stop.
- **Video ↔ audio sync (SPEC 3.4):**
  - The encoder's PTS are the camera's sensor timestamps. On Android 13+ the encoder output is pinned to the sensor timestamp base. Older versions may get them converted to MONOTONIC; `stopRecording` detects which by matching the first PTS against the take's capture results.
  - Time of the first frame = timestamp + exposure/2 + rolling-shutter skew/2 (middle of the exposure, middle row).
  - The sensor clock is detected at runtime by comparing frame timestamps with CLOCK_MONOTONIC and CLOCK_BOOTTIME. The declared `SENSOR_INFO_TIMESTAMP_SOURCE` is only a tie-break. BOOTTIME is converted to MONOTONIC.
  - The audio engine now exports its **output clock** (median of `t·sr − framePosition` over the take, from `getTimestamp(CLOCK_MONOTONIC)`). From it, `AlignmentMath.projectPositionAt(t)` gives the project position being heard at camera time `t`, which is stored as `TakeVideo.startPositionFrames`. "Video follows audio": the video is placed on the timeline the performer was hearing.
  - Each take stores its own manual video offset (`manualOffsetMs`), separate from the audio offset.
- Recorder:
  - Camera and microphone permissions are requested together; without the camera, tracks are audio-only.
  - Camera panel above the list: TextureView preview with the correct aspect and display rotation (the front camera is mirrored by the platform), a Video on/off switch, a lens switch and the size/fps.
  - "Record new track" starts the video first, then the audio. Stop finalizes the MP4 as `<takeId>.mp4` next to the WAV.
  - The track shows "Vídeo (câmera …)" or "Só áudio". Free space accounts for video.
  - The diagnostics line shows the camera, sensor orientation and detected clock.
- **"Verificar sincronia" (clap test):**
  - It finds the loudest sample of the take's WAV and maps it to the video frame where that sound falls.
  - It shows that frame (highlighted, "som") with 3 frames on each side, decoded from the MP4 and mirrored like the preview.
  - Tapping another frame moves the video so that frame lines up with the sound; "Zerar" resets the offset.
- Unit tests: 33 (new: `projectPositionAt`, `TakeVideo.startFrame`, sensor clock detection: monotonic, boottime after sleep, ambiguous, unrelated).
- On the phone (front camera, built-in mic + speaker):
  - Preview OK in portrait.
  - Test take: the first frame matched its capture result ("sensor base", exposure 33 ms, skew 12 ms, sensor clock BOOTTIME). The video was placed 142 ms after the start of the audio take (camera start-up), which is plausible.
  - The sync-check dialog opened and showed the frames.
  - I deleted my test track afterwards.

### Fixed during the 2a smoke test
- **The owner's first two takes (recorded on the first 2a build) have their video placed ~33 h off.** On this phone the sensor clock is BOOTTIME. The camera service hands the encoder its timestamps already converted to MONOTONIC, and the first build converted them again. Also, the first frame's exposure could fall out of an 8 s buffer. Both are fixed: sensor timestamp base on API 33+, PTS-clock matching, and the first 120 capture results of each take kept. Those two takes can't be checked; delete them and record new ones.
- A race between two session builds (the dispatcher does not serialize across suspensions) → mutex.

### 2a checklist (owner)
1. **Preview:** open a project. You see the front camera, upright, not stretched, mirrored like a mirror. Rotate the phone to landscape: still upright with the right aspect. "Câmera: frontal" switches to the back camera and back.
2. **Recording with video:** record a track with video. The red dot shows on the preview during the take. After Stop, the track says "Vídeo (câmera frontal, 30 fps)". Record a second one with the back camera.
3. **Clap test (main criterion, SPEC 4 phase 2):**
   - Headphones or interface as usual, metronome on or off.
   - Record a take and clap once, loud, with your hands clearly visible.
   - Open "Verificar sincronia" on that track. The hands should be closing exactly on the highlighted "som" frame, or on −1/+1. **Don't tap any frame yet**: tell me which frame it was (−3…+3).
   - Repeat 3 times, ideally with each camera and in portrait and landscape. If it is consistently off by the same amount, that is a systematic offset I can fix in the mapping.
   - The highlighted frame is placed by the loudest sound in the take, so avoid louder sounds (or count-in clicks through the speaker into the mic).
4. **Manual video offset:** tap a neighbouring frame. The dialog recomputes with the video moved, and the track shows "vídeo ±x ms". "Zerar" goes back to 0.
5. **Audio-only:** turn "Vídeo" off and record: the track says "Só áudio". Deny the camera permission in system settings, then reopen: a message instead of the preview, and recording still works.
6. **Interruptions:** go home in the middle of a take with video. The take is kept with its video, just as audio takes were in phase 1.
7. Optional: send me a take's MP4 + WAV (or `adb pull` the project) and I can check the frame numerically.

### 2a known issues / notes
- If the app is killed in the middle of a take, the MP4 is lost (it is only finalized at Stop). The WAV is recovered as before. Fragmented MP4 would fix this; to decide later.
- The video's place on the timeline does not include the calibration residual. The residual is mostly the input/analog path, which the video does not go through. If the clap test shows a constant error equal to the residual, I will add it.
- Video plays nowhere yet (the studio / multi-video player is phase 3). The sync check is the only way to look at it for now.
- Lens/resolution choice and AE/AWB/AF locks, grid, stabilization and ghost come in 2b.
- The preview runs during calibration; it does not affect the measurement, but it does use CPU.

### 2b — Done (confirmed by owner on 2026-11-08)
- **Per-project camera settings** (`Project.camera`, saved in `project.json`; projects from before simply get the defaults). These are: lens, resolution, frame rate, stabilization, grid, ghost track + opacity, and locks per lens.
- **Format:** chips for 720p / 1080p / 4K and 24 / 25 / 30 / 60 fps. Only combinations that this camera *and* the H.264 encoder support are shown, detected from the stream configuration (minimum frame duration), the AE fps ranges and `MediaCodecInfo.VideoCapabilities`. Bit rate: 8 / 16 / 45 Mbit/s, ×1.5 above 30 fps. If a saved format is not supported (e.g. after switching lens), the closest one is used.
- **Stabilization** on/off (EIS and OIS, whichever the camera has; off by default). The chip is hidden if the camera has neither.
- **AE/AWB/AF locks** — "para manter a aparência consistente entre trilhas":
  - A lock stores the values auto-exposure / white balance / focus are using at that moment (exposure time + ISO, WB gains + color transform, focus distance) in the project, per lens.
  - When the camera supports manual control (`MANUAL_SENSOR` / `MANUAL_POST_PROCESSING`), those exact values are set manually. Every later track, even after closing the app, gets the same look.
  - Otherwise the plain platform lock flag is used, marked with "*". It holds only while the camera stays open and is re-applied ~1 s after the camera reopens, once 3A has settled.
  - The focus chip is hidden on fixed-focus lenses.
- **Grid** (rule of thirds) over the preview.
- **Ghost (onion skin):** choose a previous track with video. A still from about 1 s into its active take is drawn over the preview with adjustable opacity, mirrored the way that track looked in its own preview. It is not recorded.
- Reopening the camera (lens/format change) keeps the preview view, so it doesn't flash.
- Unit tests: 35 (new: camera settings serialization with locks per lens; old projects get default settings).
- On the phone (front camera):
  - The card offered 720p/1080p/4K, 24/30/60 fps at 1080p and 30 fps at 4K, and all three locks (persistent).
  - Grid shown. The exposure lock kept ISO 2901 / 33 ms, and a later 4K take used exactly that exposure. The ghost overlay was visible.
  - A 4K take recorded fine and was aligned like the 1080p ones.
  - I restored this project's camera settings afterwards and deleted my test take.

### Fixed during the 2b smoke test
- **Crash when changing format:** reopening the camera removed the preview view, whose surface died while the session was being rebuilt. Now an invalid surface is skipped, session setup never throws, and the view is kept during reopenings.

### 2b checklist (owner)
1. **Format:**
   - Card "Câmera" in the recorder: the resolutions and frame rates offered make sense for each camera (switch front/back and compare).
   - Record a take at 720p, one at 4K (if offered) and one at 60 fps (if offered). Each records, and "Verificar sincronia" still gives the clap on the "som" frame (±1).
2. **Stabilization** (if shown): walk while recording with it on and off; the "on" take is steadier (the image may crop a little).
3. **Locks:**
   - In a scene with changing light (e.g. move a lamp), with no lock the brightness/color adapt.
   - Lock exposure + white balance: the image stops adapting.
   - Record two tracks, leave the project, come back, record a third: all three look the same.
   - Do they have "*"? If so, they only hold while the camera is open; tell me which ones.
   - Focus lock (back camera): focus on something, lock, move the phone; the focus stays.
4. **Grid:** toggles the thirds lines over the preview; they are not in the recorded video.
5. **Ghost:**
   - Pick "Fantasma → Trilha 1": that track's image appears over the preview.
   - Opacity slider works live and is remembered.
   - "Nenhum" removes it. Deleting that track removes the ghost.
6. **Persistence:** all of the above is remembered per project (open the other project: it has its own settings).

### 2b known issues / notes
- A lock measured at one frame rate is reused as is. If you lock at 24 fps and then switch to 60 fps, the exposure is capped at the frame duration (darker).
- The ghost uses the take's frame at ~1 s; choosing a specific frame could come later if needed.
- 25 fps is offered only where the camera has a matching AE range (not on the test phone).
- Default camera/quality settings for new projects belong to phase 2.8 (preferences); for now each new project starts at front / 1080p30 / no locks.

### 2c — Done (confirmed by owner on 2026-11-08)
- **Takes (SPEC 2.2):**
  - Each track has "Regravar". It records a new take into the same track, which becomes the active one; earlier takes are kept.
  - During a re-take the other tracks play and the track's old take is silent (it is being replaced).
  - A re-take that records nothing (stopped during the count-in, or failed to start) is dropped, and the previous active take comes back.
- **Takes dialog** ("Takes (n)", shown when a track has more than one take):
  - newest first, with duration, video (camera, resolution) or audio-only, and date/time
  - pick the active take (it is what plays, and later what is exported)
  - delete a take (with confirmation; audio and video files are deleted)
  - the last take of a track can only go with the track
- The track title shows "take k de n". **Deleting a track now asks for confirmation** (it deletes all its takes).
- **Audio-only tracks:** the "Vídeo" switch decides it per recording, so a track (or a single take) can be audio-only; the take list and track card say "Só áudio". Lens and resolution are also per take.
- Ghost: while a track used as the ghost is being re-taken, the ghost keeps showing that track's previous take, so you can repeat the framing.
- On the phone:
  - Recorded a track, re-recorded it ("take 2 de 2"; log "Recording take 2 of track …", only 2 tracks playing).
  - Opened the takes dialog, made take 1 active, deleted take 2 with confirmation.
  - Deleted the track with confirmation; its files were removed and the project was left with your two tracks only.
  - **Not tested on the device:** dropping a re-take stopped during the count-in (needs a stop within 2 s).

### 2c checklist (owner)
1. **Re-take:**
   - Record a track, then "Regravar" on it while other tracks exist: you hear the other tracks, not the old take of this one.
   - After Stop the card says "take 2 de 2", and playback uses the new take.
2. **Choosing:**
   - "Takes (2)": switch to take 1; playback uses it at once, with its own alignment and video.
   - Switch back. The manual offset and "Verificar sincronia" act on the active take.
3. **Deleting:**
   - Delete a non-active take: it disappears, the active one stays.
   - Delete the active take: the newest remaining becomes active.
   - With one take left, its delete button is disabled.
   - Deleting a track asks first.
4. **Dropped re-take:** "Regravar" and stop during the count-in: no new take is left behind and the previous take is still active.
5. **Audio-only:** turn "Vídeo" off and re-record a video track: that take is "Só áudio". Turn it on again and re-record: video again. Mixed takes in one track work.
6. **Ghost + re-take:** set the ghost to a track and re-record that same track: the ghost stays visible during the take.
7. **Phase 2 criterion (SPEC 4):** clap test ±1 frame (passed in 2a: 0 frames). With 2c, everything in phase 2 is implemented: video without audio synced to the engine, front/back, AE/AWB/AF locks, ghost, grid, takes.

### 2c known issues / notes
- Track names are still "Trilha N" by position; renaming tracks isn't in the SPEC for now.
- The take dialog shows the take number by recording order; numbers shift after a deletion.
- Deleting a track now also removes its folder. Empty folders left by tracks deleted before 2c stay (harmless).

## Phase 3 — Canvas and preview
**Decision (owner, 2026-11-08):** the canvas preview lives in the recorder screen for now (the top panel switches Camera / Canvas). `:feature:studio` stays a placeholder. The Studio/recorder split (SPEC 3.2) is postponed to Phase 7, because it needs the audio session moved out of the recorder. The layout editor (3c) will be its own screen.

### 3a — Done (confirmed by owner on 2026-11-08)
- **Model (`:core:model`):**
  - `Project.canvas: CanvasSettings` (background color + user layers; additive, schema still 1).
  - `Layer` with normalized coordinates (SPEC 3.5): frame on the canvas, crop of the displayed video, z, opacity, corner radius, border width/color, visible.
  - `CanvasLayout`:
    - auto-grid: picks the columns × rows that keep the most picture after a filling crop; empty cells count as loss; ties stack on portrait/square canvases and go side by side on landscape;
    - centered incomplete last row;
    - centered "cover" crop;
    - `effectiveLayers(project)`: stored layer, or an automatic cell for tracks without one.
  - Tests: `CanvasLayoutTest` (5).
- **Presentation clock (`:core:audio`):**
  - The engine status now carries `PresentationClock`: output timestamp clock + output frame and project frame where the transport started.
  - `positionAt(t)` gives the project position being *heard* at CLOCK_MONOTONIC time t, through the same `AlignmentMath.projectPositionAt` used for video placement in 2a.
  - Stable between the 50 ms status updates, so the video can evaluate it on every display frame.
- **`:core:video`** (SPEC 3.1/3.5), public API `CanvasPreview` / `CanvasPreviewFactory` / `CanvasScene` / `PositionSource`:
  - **Compositor:**
    - OpenGL ES 3 on its own render thread; Choreographer drives it at the display rate.
    - Recordable EGL config, so the same renderer can later feed the export encoder.
    - One quad per layer. The shader handles the UV crop, the display rotation (the codec is told not to rotate), rounded corners with anti-aliasing, border and opacity.
    - Background color; letterboxing outside the canvas.
  - **Decoders:** one MediaCodec decoder thread per layer, rendering into a SurfaceTexture.
    - Each vsync the compositor sets every layer's target media time from the audio clock, plus 3 vsyncs for display latency.
    - A decoder renders the frame nearest the target; late frames are released without rendering (dropped), never waited for.
    - Jumping back, or more than 1.5 s ahead, seeks to the previous sync frame.
    - Only one frame is in flight per layer.
    - Before the first frame the layer shows the first frame; past the end it holds the last frame.
  - Pauses when the screen stops. A diagnostics line every 3 s while frames move (`canvas: pos=… shown=… dropped=…`).
- **Recorder screen:**
  - "Câmera / Canvas" chips above the panel. The camera keeps running under the canvas, so switching back is instant and never rebuilds the capture session.
  - Starting a recording switches to Câmera.
  - Canvas mode shows a playhead slider (time / length). Dragging it while stopped shows that moment.
  - "Tocar" now starts from the playhead; after stopping, the playhead stays put (return to start). Recording still starts from 0.
- **On the phone (S938B, "Música 1"):**
  - With a fresh test track: 3 layers in a 2×2 grid with the last one centered.
  - Playback at 30 fps, `shown=90` per 3 s with **0 dropped**.
  - Scrubbing, Câmera↔Canvas, background and back: no crash. Almost no CPU in the background.
  - The test track was deleted afterwards (and its folder removed). I also removed 3 empty folders left by my phase 2 test tracks.

### Fixed during the 3a smoke test
- The decoder kept re-seeking (141 frames shown per 3 s for 30 fps video, thousands dropped): the frame it holds for later, up to 1.5 frames ahead, was mistaken for "the clock went backwards". Backward seeks are now decided from the last frame actually shown.
- The render loop kept running with the app in the background → paused on screen stop.

### 3a checklist (owner)
1. Open a project with 2+ video tracks recorded **after** 2a's timestamp fix (see note below) → "Canvas": the videos appear in a grid on a 9:16 canvas, upright and cropped to fill their cells.
2. **Sync:** "Tocar" with the canvas visible. Each video follows its own audio (lips/hands match what you hear); none freezes or runs ahead. A clap test is the most precise check: record 2 tracks clapping on the metronome, then watch the claps in the canvas.
3. **Playhead:** drag the slider while stopped: the videos jump to that moment. "Tocar" starts from there, and after Stop it goes back there.
4. **Robustness:** switch Câmera ↔ Canvas during playback; leave the app and come back; record a new track while in Canvas (it switches to Câmera, and the new track joins the grid afterwards).
5. Optional: `adb logcat -s Selfband:V` during playback: `dropped` should stay (almost) constant.
6. Tell me whether the video looks early or late compared with the sound in the canvas. The display latency is an estimate (3 vsyncs) and is easy to adjust.

### 3a known issues / notes
- **Your two original takes in "Música 1"** were recorded before 2a's timestamp fix (their video start is ~33 h off). The canvas therefore shows their **last frame, frozen**. Re-record them, or record new tracks, to see them move.
- The display latency compensation is a fixed estimate (TextureView composition + display). If the canvas looks consistently early or late, I can tune it, or measure it in Phase 7.
- Video is not mirrored in the canvas (it is what the camera recorded). A per-layer "flip" can come with layer editing in 3c if you want selfie-style.
- The recorder screen recomposes on every engine status update (~20×/s). With the camera preview this costs ~30% of a core on the main thread. It predates 3a; I'll optimize it in Phase 7 (UI).
- No decoder-instance limit yet: proxies and the instance limit are Phase 6 (SPEC 3.5). On this phone 9 decoders should be fine; weaker phones may fail to start some layers (they then show a gray placeholder).

### 3b — Done (confirmed by owner on 2026-11-08)
- **Formats (SPEC 2.6):** 9:16, 16:9 and 1:1, chosen in the new "Canvas" card. The card replaces the camera settings card while the panel shows the canvas. The format is stored in `project.aspectRatio`.
- **Layout templates as data:**
  - `core/model/src/main/resources/.../layout-templates.json` holds the built-in templates 1, 2, 3, 4, 6 and 9, plus "Auto" (the 3a grid, which adapts to the number of tracks and the shape of the videos).
  - A template can have a slot list per format, plus a `default`:
    - "2": stacked on 9:16, side by side on 16:9 / 1:1;
    - "3": one on top and two below on 9:16, three columns otherwise;
    - "6": 2×3 on 9:16, 3×2 otherwise.
  - Video tracks fill the slots in track order. Tracks beyond the last slot are hidden, and the card warns ("N trilha(s) … ficam ocultas").
  - The choice is stored in `project.canvas.templateId`. `null` is reserved for a free layout (3c).
  - Thumbnails are drawn from the template data at the current format, so there are no image assets.
- **Nothing is lost when switching (SPEC 2.6):**
  - Each layer's stored crop is the user's intention (center + zoom). It is refit to the cell's shape at draw time, without distortion, and the stored crop itself never changes. So 9:16 → 16:9 → 9:16 comes back exactly the same.
  - Other layer properties (opacity, border, corners, z) are kept.
  - Ready for 3c's crop editing.
- Tests: `CanvasLayoutTest` now has 12 tests (templates valid and tiling the canvas in every format, crop refit keeps full/zoom/center, extra tracks hidden, format switch uses the right variant without distortion, unknown template falls back to Auto, free layout survives serialization). 47 unit tests in total.
- **Fix:** in the track card, "Verificar sincronia" moved to the button row next to "Regravar". The video line was being squeezed into one letter per line.
- On the phone, in a test project with 2 video tracks (deleted afterwards):
  - 9:16 Auto stacked; 16:9 Auto side by side; "1" showed one track plus the hidden notice; 1:1 "3" gave three columns with the third empty.
  - Playback in 1:1 / "4" ran at 30 fps with 0 dropped frames.
  - Format and template are saved in project.json.

### 3b checklist (owner)
1. In a project with 2–4 video tracks, open "Canvas". The "Canvas" card shows Formato (9:16 / 16:9 / 1:1) and the Layout thumbnails.
2. **Formats:** switch between the three: the preview changes shape, and Auto rearranges (stacked on 9:16, side by side on 16:9).
3. **Templates:** try 1, 2, 3, 4, 6, 9 in each format:
   - the preview matches the thumbnail;
   - with more tracks than spaces, the extra ones disappear and the card says how many;
   - empty spaces show the background (black).
4. **Persistence:** leave the project and come back: format and layout are kept.
5. **Playback:** play in a non-default format/template; the videos stay in sync as in 3a.
6. **Track card:** the "Vídeo (câmera …)" line reads normally; "Verificar sincronia" is next to "Regravar".

### 3b known issues / notes
- **Your projects:** at 12:55 today, between my 3a smoke test and the start of 3b, both projects ("Música 1" and "Música 2") were deleted from the phone. The only code path that removes a project folder is "Excluir projeto" on the Projects screen, so I assume it was done there while testing. If it wasn't you, tell me and I'll investigate.
- Saving your own layouts as templates (SPEC 2.6) needs the layer editor → 3c.
- Template choice is per project. Track order decides which track goes in which space; reordering comes with 3c (z-order / drag).
- A format change briefly shows the old frame stretched until the next frame is drawn (TextureView resize).


### 3c — Done (confirmed by owner on 2026-11-08)
- **Layout editor (SPEC 2.6):** its own screen in `:feature:layout`, opened with "Editar layout" in the Canvas card. The canvas shows the frame at the playhead (slider, no audio).
- **Gestures on the canvas:**
  - tap selects the topmost layer under the finger (or pick it with the "Trilha N" chips, which also reach hidden layers);
  - **Mover** mode: drag moves; pinch or drag a corner scales, keeping the shape (the opposite corner stays put);
  - **Recortar** mode: the dashed outline shows the whole video. Edge/corner handles crop without moving or scaling the video on screen. Drag inside reframes, pinch zooms the video. "Restaurar recorte" undoes it.
- **Snap:** while moving, the layer's edges/center snap to the canvas edges/center and to the other layers' edges/centers (guide lines are drawn). With no guide in reach, it snaps to a 12-step grid. There is a switch to turn snap off.
- **Selected layer controls:**
  - align (left/center/right, top/middle/bottom);
  - Para frente / Para trás (z-order);
  - Visível;
  - sliders for opacity, corners and border, with 8 border colors.
- **Free layout:**
  - The first edit freezes the current template into stored layers (`templateId = null`), so the picture does not jump.
  - The Canvas card then selects no template and says "Layout livre". Picking a template replaces it.
  - Edits show live and are saved to project.json at the end of each gesture or slider drag.
- Video decoders are released when a screen stops, so the recorder and the editor never hold codecs at the same time.
- Math lives in `core:model` (`LayerEditing`). The video is never distorted: the crop keeps the frame's pixel shape. 7 new tests (`LayerEditingTest`), 54 unit tests in total.
- On the phone, in a test project with 2 video tracks (deleted afterwards), all of these worked and were saved in project.json:
  - select, move, corner scale (it pivots on the opposite corner);
  - top-edge crop (the video stays in place);
  - align center, Para frente;
  - opacity, corners, border.
  - The recorder's canvas showed the same free layout afterwards, with no errors in logcat.

### 3c checklist (owner)
1. In a project with 2+ video tracks, open "Canvas", then "Editar layout".
2. **Select:** tap a layer. It gets an outline with corner handles and its "Trilha N" chip lights up. Tap empty space to deselect.
3. **Move:** drag a layer. Near the canvas center/edges or another layer's edges it snaps and a guide line appears. Turn snap off: it moves freely.
4. **Scale:** pinch on the layer, and drag a corner. The shape is kept and the video is not stretched.
5. **Crop:** switch to "Recortar":
   - drag the edge and corner handles: the window shrinks/grows, but the video does not move or get squashed;
   - drag inside: the video reframes;
   - pinch: the video zooms inside the window;
   - "Restaurar recorte" brings back the whole frame.
6. **Order / look:**
   - Para frente / Para trás change which layer is on top;
   - Visível hides the layer (its outline goes dashed);
   - opacity, corners and border change live, and the border color swatches work.
7. **Playhead:** move the slider. The canvas shows that moment of every video.
8. **Persistence:** go back. The recorder canvas shows the same layout and the card says "Layout livre". Leave the project and come back: it is kept. Play: the videos stay in sync.
9. Pick a template in the card: it replaces the free layout.

### 3c known issues / notes
- Pinch could not be tested from adb (single touch only). Please check it carefully (items 4 and 5).
- One drag of a crop handle during my test moved much less than the finger, and I could not reproduce it. If a handle "lags behind" the finger, tell me.
- No undo yet. Picking a template after hand edits discards them.
- Next (3d, after your OK): background (color / blur of a track / image), saving your own layouts as templates, global trim.

### 3d — Done (confirmed by owner on 2026-11-08)
**Scope (owner, 2026-11-08):** following SPEC §4, 3d is only the canvas background. Global trim moves to Phase 4 (with the waveform offset) and user-saved templates to Phase 6.
- **Background (SPEC 2.6):** in the layout editor, with no layer selected, the "Fundo" section offers:
  - **Cor:** 10 swatches.
  - **Desfoque:** one track's video covering the canvas, blurred and slightly darkened, in sync with the layers. You pick the track; if it disappears or loses its video, the first video track is used.
  - **Imagem:** a picture from the system photo picker (no storage permission). It is copied into the project folder upright, scaled down to at most 2048 px, as JPEG, so deleting the original does not affect the project. "Trocar imagem" replaces it and deletes the old copy.
  - The color stays visible until the video or picture is ready, and is used if they are missing. Switching kind keeps the others' settings (back to Imagem shows the same picture).
- **Compositor:**
  - The blur draws the video into a 1/8-size framebuffer, runs 2 pairs of separable Gaussian passes and scales the result over the canvas.
  - A video that is both a layer and the blur source uses one decoder.
  - The picture is decoded off the render thread.
  - The same code draws the recorder's canvas and the editor's.
- Model: `CanvasSettings.background` (COLOR / BLUR / IMAGE), `backgroundTrackId`, `backgroundImage`. Older projects read as a color background. 2 new tests (blur source fallbacks, old JSON), 56 unit tests in total.
- **Build fix:** incremental KSP once generated no Hilt code for `:feature:recorder` and `:feature:layout` after `:core:video` changed. The build passed but the app crashed opening a project ("Cannot create an instance of RecorderViewModel"). `gradle.properties` now has `ksp.incremental=false`.
- On the phone, in a test project with 2 video tracks, all of these worked:
  - color; blur of track 1 and track 2, following the playhead;
  - picture picked from the gallery (a test screenshot I pushed and then deleted): upright, covering the canvas, saved in the project folder;
  - in the recorder, the blurred background played at 30 fps with no new drops.

### 3d checklist (owner)
1. In a project with 2+ video tracks, open "Editar layout". Make room to see the background, e.g. shrink a layer (this turns the template into a free layout), or use 1:1 / 16:9 with a template that leaves empty spaces.
2. Deselect all layers (tap an empty spot). The "Fundo" section appears below the hint.
3. **Cor:** pick a few colors; the empty areas change.
4. **Desfoque:** the empty areas show a blurred, darker version of track 1. Switch to Trilha 2. Move the playhead: the blur follows the video.
5. **Imagem:** choose a photo. It appears upright and fills the canvas (cropped, not stretched). "Trocar imagem" swaps it.
6. Go back to the recorder and play: the background shows there too and the videos stay smooth and in sync.
7. Leave and reopen the project: the background is kept. Delete the original photo from the gallery: the project still shows it.

### 3d known issues / notes
- The test project "Teste3d" was left on the phone for the owner to delete.
- The blur strength and darkening are fixed (no slider). Tell me if you want them adjustable.
- With the image chosen, the Google Photos picker on this phone asks to confirm with "Concluir" even for a single photo. That is the system picker's behavior.
- After 3d, Phase 3 is complete (once you confirm).

## Phase 4 — Basic mixing

### 4a — Volume, pan, mute/solo, meters, master + limiter (confirmed by the owner on 2026-11-08)
- Recorder panel has a third chip, **Mixer**. The panel above shows the canvas and the playhead, like Canvas mode. The card below becomes the mixer.
- Per track: M (mute) and S (solo) chips, a level meter, volume (−60..+24 dB) and pan. Tap a value to reset it (0 dB / center). Pan snaps to the center near it.
- Master: L/R meters after the limiter, volume (−60..+12 dB), clipping protection switch with the live gain reduction. "Zerar" resets the whole mix.
- Rules (in `core:model` `Mixing`, unit tested):
  - pan uses a balance law: center = unity, the sides only attenuate;
  - mute wins over solo; while any track is soloed only soloed tracks play.
- Engine: tracks mix into a stereo master bus with per-channel gain ramps (no zipper clicks).
  - Master gain is ramped, then the limiter, then the meters.
  - The limiter is stereo-linked, ceiling −1 dBFS, instant attack, 80 ms release, no lookahead. It adds no delay, so timing and calibration stay valid.
  - The old soft clip is gone; a final ±1 clamp runs after the metronome.
- Model: `Track.pan`, `Track.solo`, `Project.mixer` (`MixerSettings`: master volume, limiter on). Older projects read as centered, not soloed, limiter on. 4 new tests, 60 unit tests in total.
- Meters show silence while stopped (the engine holds the last peak when idle).
- On the phone: the Mixer chip, the card, M/S/volume/pan rows and the master row appeared in a test project with 2 audio tracks; no errors in logcat. The test project was deleted. The owner tried it and confirmed it works.

### 4b — EQ per track + send reverb (confirmed by the owner on 2026-11-08)
Built while the owner was away (no device access); installed afterwards and confirmed by the owner on the phone.

- **Track EQ** (SPEC 2.5): the "EQ" chip on each mixer row opens a dialog.
  - Contents: response curve (20 Hz–20 kHz, ±18 dB); EQ on/off (bypass, the settings are kept); high-pass 20–300 Hz (12 dB/oct, on/off); low shelf 40–500 Hz; mid peaking band 60 Hz–12 kHz with Q 0.3–8; high shelf 2–16 kHz. Gains are ±15 dB.
  - Frequency sliders are logarithmic. Tap a gain or Q value to reset it. "Zerar" flattens the EQ.
  - The chip is highlighted when the EQ changes the sound.
- **Phase invert**: the "Ø" chip (SPEC lists it as optional/advanced). It is folded into the track gains as a negative gain.
- **Reverb send**: "Rev" slider per track, −∞…+6 dB, post-fader (a muted or silenced track sends nothing). Off by default.
- **Reverb** section in the mixer card:
  - presets Sala pequena / Sala / Hall / Plate;
  - size, decay (RT60 0.3–8 s), pre-delay 0–200 ms, damping, return level (−∞…+6 dB);
  - moving a room control turns the preset into custom; the return level keeps the preset.
- **Native DSP** (`core/audio/src/main/cpp/engine`):
  - `Effect.h`: the common interface from SPEC 3.3 (`prepare`, `reset`, `process`, `setParam`).
  - `Equalizer.h`: RBJ cookbook biquads in transposed direct form II.
    - Parameters are atomics, smoothed per block (~30 ms); coefficients are recomputed only when something moved.
    - Bypass and high-pass on/off crossfade over ~20 ms.
  - `Reverb.h`: Freeverb-style design, written for this project (8 damped combs + 4 allpasses per channel, stereo spread, pre-delay).
    - Size scales the delay lengths. Buffers are allocated at the largest size in `prepare()`.
    - Decay sets each comb's feedback for the requested RT60, so size and decay are independent.
  - Signal flow per track: EQ (pre-fader) → gain/pan/phase ramp → master, plus a post-fader send → reverb bus.
    - The reverb's wet output returns to the master before master volume and the limiter.
  - EQ, reverb and limiter state reset when the transport starts.
  - Nothing on the audio thread allocates, locks or logs.
- **Model:** `Track.eq` (`TrackEq`), `Track.reverbSendDb`, `Track.phaseInverted`, `MixerSettings.reverb` (`ReverbSettings`, `ReverbPreset`). Older projects read with a flat EQ, no send, the "Sala" reverb.
  - `EqMath` in `core:model` has the same RBJ formulas as the native EQ (curve + tests).
- "Zerar" in the mixer now also clears phase invert. It keeps EQ, sends and the reverb (they have their own reset and presets).
- 8 new tests (EQ response: flat, peak, high-pass −3 dB / 12 dB per octave, shelves, bypass; phase; presets; old JSON). 68 unit tests in total.

### 4b checklist (owner)
Use headphones for the reverb tests, and a project with 2+ tracks (ideally one with a voice or a percussive instrument).
1. Open the project, tap **Mixer**. Each track row now has **EQ**, **Ø**, **M**, **S** and a **Rev** slider. Below the tracks is the **Reverb** section, then Master.
2. Play. Raise **Rev** on one track: the reverb appears, and only on that track. Lower it to −∞: the tail fades out naturally (no cut, no click).
3. Try the presets **Sala pequena → Sala → Hall → Plate** while playing: each sounds clearly different, with no pops. Move **Decaimento**: the tail gets longer or shorter. **Tamanho** may cause a brief artifact while dragging (expected: the delay lengths jump).
4. **Retorno** to −∞ removes all reverb; back to 0 dB brings it back.
5. Mute a track that has a send: its reverb stops too (post-fader). Solo another track: only its reverb is heard.
6. Tap **EQ** on a track. Turn on **Passa-altas** and move it to ~300 Hz: the lows go away; the curve shows it. Boost **Banda de médios** +12 dB and sweep the frequency: a clear "wah" sweep with no zipper noise. Try the shelves.
7. Switch **EQ ligado** off and on while playing: it switches smoothly (no click). The curve goes gray when off. **Zerar** flattens it. The **EQ** chip is highlighted only while the EQ changes the sound.
8. **Ø** on one of two tracks with the same content (or two mics on one source): they partly cancel. Off restores it.
9. Leave and reopen the project: EQ, sends, phase and reverb settings are kept.
10. Record a new take while a reverb/EQ is active: the recording is unaffected (dry input) and still aligned. Check the logcat for errors and xruns during playback with reverb on.

### 4b known issues / notes
- Confirmed on the device by the owner.
- Dragging the reverb **size** can cause a short artifact (the delay lengths jump; smoothing them would bend the pitch of the tail).
- The EQ and reverb also need to run in the Phase 5 export render; they are written as plain classes so the offline mix can reuse them.

### 4c — Offset by waveform + global trim (confirmed by the owner on 2026-11-08)
- **Timeline card** in Mixer mode, above the mixer card: one waveform lane per track on a shared time axis, with a ruler, the playhead and the global trim.
  - Drag a lane sideways to move that track. This changes the manual offset of its active take; the WAV is never rewritten. The lane keeps its new place and the engine reloads the tracks on release.
  - Zoom: **+ / − / Ajustar**. At the closest zoom 1 px = 2 frames. While zoomed, drag the ruler (away from the handles) to scroll.
  - Below the timeline, the selected track (tap a lane) shows its manual offset in ms and samples, with nudges −10 ms, −1 ms, −1 sample, +1 sample, +1 ms, +10 ms and "Zerar" (sample precision, SPEC 2.5). Typing the offset in ms is still in the pencil dialog of the track card.
  - Moving the audio does not move the video: the video offset stays separate (SPEC 2.5), still set in "Verificar sync".
  - Editing is off while playing or recording (dragging then only scrolls).
- **Global trim** (SPEC 2.6): drag the two handles on the ruler. The cut parts are dimmed and the canvas shows the frame at the handle while dragging. There are also buttons "Início no cursor", "Fim no cursor" and "Sem corte".
  - Playback stays inside the trim: it starts at the trim start if the playhead is outside, and stops at the trim end.
  - Recording is not limited by the trim.
  - An end at the project end is stored as "until the end", so it follows longer takes recorded later.
  - Minimum trimmed length: 100 ms. The export (Phase 5) will use this range.
- **Waveforms:** peaks of each take (loudest channel, every 64 frames) are computed once in the background. They are cached next to the WAV (`<take>.wav.peaks`) and deleted with the take.
  - Coarser levels let a zoomed-out view read few values.
  - Bars use a −48..0 dBFS scale so quiet takes stay visible.
- **Model:** `Project.trimStartFrame`, `Project.trimEndFrame` (null = project end), `trimStart` / `trimEnd` / `isTrimmed`, `Trim` (normalize, play start), `Waveform` (multi-level peaks).
  - Older projects read untrimmed.
  - 6 new tests (trim clamping, minimum length, play start, old JSON, waveform peaks vs brute force at every zoom). 74 unit tests in total.

### 4c checklist (owner)
Use a project with 2+ tracks; ideally one recorded with a loud clap at the start.
1. Tap **Mixer**. Above the mixer is **Linha do tempo**, with one waveform per track. Waveforms appear within a second or two the first time and instantly afterwards.
2. Drag track 2 sideways: it moves, and after release the offset text below updates. Play: track 2 is heard earlier/later accordingly.
3. Zoom in with **+** several times until the claps of two tracks are wide. Drag one clap onto the other, then refine with **±1 ms** / **±1 amostra**. Play: the claps sound as one. **Zerar** puts the track back where the automatic alignment put it.
4. Scroll while zoomed by dragging the ruler. **Ajustar** shows everything again.
5. Drag the **left handle** on the ruler to the right: the start gets dimmed and the canvas shows the frame at the handle. Do the same with the **right handle**. Below, "Corte: a → b (duração)" appears.
6. Play from the beginning: playback starts at the trim start and stops by itself at the trim end.
7. Tap the ruler to move the playhead inside the trim and play: it starts there. Try **Início no cursor** / **Fim no cursor** and **Sem corte**.
8. While playing, dragging a lane only scrolls; the nudges are disabled.
9. Leave and reopen the project: offsets and trim are kept. Record a new track: it records normally (not limited by the trim). Delete a take: no error.
10. Check that the video of a moved track still matches its own audio. If it doesn't, use "Verificar sync": the video offset is separate.

### 4c known issues / notes
- No undo for drags yet; use "Zerar" or the nudges.
- Pinch-to-zoom is not supported; use the + / − buttons.
- The playback stop at the trim end is checked every ~50 ms (status rate), so preview may run a few ms past the end. The export will cut exactly.
- Phase 4 is complete.

## Phase 5 — Export

### 5a — Audio export: mix + stems (confirmed by the owner on 2026-11-08)
- **Shared mix graph:** the live engine and the offline renderer now use the same code (`engine/MixGraph.h`).
  - Per track: EQ → volume/pan/phase → master, plus the reverb send.
  - Master: reverb return → master volume → limiter.
  - The metronome is only in the live engine and is never exported.
  - `AudioEngine` was refactored onto it, with no change in sound.
- **Offline renderer:** `OfflineMixer` (C++) and `OfflineMix` (Kotlin, JNI) render blocks of 4096 frames at any position, faster than real time.
  - The mix is stereo.
  - Each stem keeps its own channel count and is dry: only the take, aligned, without volume/pan/EQ/reverb.
  - `PlaybackTracks.of` gives playback and export the same track list (active takes, offsets, mute/solo).
- **Range:** the global trim (or the whole project). All files start at the trim start and have exactly the same length, so stems drop into a DAW in sync. The reverb tail is cut at the trim end.
- **Files:** WAV 24-bit PCM (default) or 32-bit float, at the project sample rate.
  - They are saved in `Music/Selfband/<project>/` as "<project> - mix.wav" and "<project> - trilha 01.wav", numbered by track position. Android does not accept audio under Movies, so the video (5b) will go to `Movies/Selfband/`.
  - Files are written straight into MediaStore as hidden (pending) items and only appear when complete. They are deleted on cancel or error.
- **Background:** `ExportService` is a foreground service with a progress notification and a **Cancelar** action. Its type is mediaProcessing on Android 15+, dataSync before that.
  - The export keeps running if the app goes to the background or the screen turns off.
  - Notifications are requested on the first export (Android 13+). Exporting works without them.
- **Screen:** recorder top bar → **Exportar**, which is enabled when the transport is stopped and the project has audio.
  - Options: Mix (WAV) and Stems (WAV) checkboxes, 24-bit / 32-bit float, destination.
  - While running: progress, step (mix / stem n of m) and Cancel.
  - When done: the file list, **Compartilhar** (system share sheet with all files) and **Nova exportação**.
- **Modules:** new `:core:export` (manager, service, WAV writer, MediaStore) and `:feature:export` (screen). Wired in `:app` navigation.
- 3 new tests: 24-bit header/samples/clipping, float multi-block, file names. 77 unit tests in total.
- **Fix after the first owner test:** exporting crashed on Android 17 with "Starting FGS with type none".
  - Cause: `ServiceCompat.startForeground` masks the service type to the types known on API 34. That drops `mediaProcessing` (API 35) and passes no type at all.
  - Fix: the service calls `Service.startForeground(id, notification, type)` directly (available since API 29 = minSdk).
- Checked by me on the phone, project "Música 2" (3 tracks), mix + stems at 24-bit:
  - 4 files in `Music/Selfband/Música 2/`, all 340 456 frames (7.093 s) at 48 kHz;
  - mix stereo with peak −9.5 dBFS; stems mono, all with signal.

### 5a checklist (owner)
Use a project with 2+ tracks, some mixer settings (volume, pan, EQ, reverb) and a trim.
1. In the recorder, tap **Exportar** (top right). The screen shows the range (trim), Mix/Stems options, format and "Salvo em Music/Selfband/<projeto>/".
2. Leave **Mix** on, tap **Exportar**, and answer the notification prompt as you like. Progress runs; with notifications allowed, a notification with progress and Cancelar appears.
3. When it ends, the file is listed. Tap **Compartilhar** and send it somewhere (or open it in a player). It should sound exactly like the preview of the trimmed range, without the metronome.
4. Tap **Nova exportação**, turn on **Stems**, choose **32-bit float**, export. You get one file per track, plus the mix if still on.
5. Load the stems into a DAW (or check the lengths in a file manager): all stems are the same length, start together and are in sync. They are dry: no volume, pan, EQ or reverb.
6. Start a long export and press Home or turn the screen off: it keeps going and finishes. The notification says it's done.
7. Start another export and tap **Cancelar** (on the screen or in the notification): it stops and no partial file is left in Music/Selfband.
8. Check the folder in a file manager or music app: Music/Selfband/<projeto>/ contains the WAVs.

### 5a known issues / notes
- One export at a time. Exporting another project while one runs is blocked with a message.
- Exporting a file with the same name again does not overwrite it: Android adds " (1)" etc.
- The Exportar button is disabled while playing or recording.
- Video export: see 5b.

### 5b — Video export: MP4 H.264 + AAC (confirmed by the owner on 2026-11-08)
- **Same renderer as the preview (SPEC 2.6):** the drawing code moved out of `GlCanvasPreview` into `CanvasDrawer`, which handles the background color, blurred video or picture, then the layers with crop, rotation, corners, border and opacity.
  - The live preview and the export both call it, so the file looks like the preview.
  - The preview itself is unchanged.
- **Offline render:** `CanvasRenderer` (`:core:video`) draws into the encoder's input surface on the export thread.
  - Each video has a `FrameStepDecoder`. It picks frames with the same rule as the preview: nearest frame, first frame before the video starts, last frame after it ends.
  - It waits for every frame instead of dropping late ones, so the export never skips frames even when the phone is slow.
- **Clock:** the audio frame position drives everything.
  - Video frame k shows project position `trimStart + k · sampleRate / 30` and is stamped k / 30 s. Audio sample n is stamped n / sampleRate.
  - The audio is the same mix as the WAV: same graph, metronome excluded. Effect state is reset before each pass, and the mix WAV made after a video is bit-identical to one made alone.
- **AAC delay:** the AAC encoder puts silence (priming) before the sound, so a naive MP4 would have the audio late. The export measures that delay at run time and delays the video by the same amount, written as an MP4 edit list.
  - The measurement encodes and decodes a click, once per sample rate per app run. It is measured, never a per-device constant.
  - Measured on the test phone: 2048 frames (42.7 ms).
- **Encoding:** H.264 with High profile when the encoder offers it (otherwise its default). Size follows the canvas with a short side of 1080: 1080×1920 for 9:16, 1920×1080 for 16:9, 1080×1080 for 1:1. 30 fps, keyframe every 1 s, automatic bitrate (~12.5 Mbps at 1080p30), BT.709. AAC-LC stereo at 256 kbps and the project sample rate (48 kHz on the test phone). MediaMuxer writes the MP4.
- **Output:** `Movies/Selfband/<project>.mp4`, written as a hidden (pending) MediaStore item and removed on cancel or error.
  - WAVs still go to `Music/Selfband/<project>/`.
  - On the screen, Vídeo (MP4) is on by default; Mix and Stems are off by default. Any combination works in one export: video first, then mix, then stems. The done screen lists every file and both folders, and Compartilhar sends them all.
- **Errors:** no suitable encoder, or a codec error, shows "Não foi possível codificar o vídeo neste aparelho" and leaves no partial file.
- 4 new tests: sizes per format, even sides, automatic bitrate, default options. 81 unit tests in total.
- Checked by me on the phone, project "Música 2" (3 tracks, 9:16, 7.09 s):
  - **Speed:** the export took about 2 s.
  - **File:** `Música 2.mp4` is 10.9 MB, 1080×1920, 213 frames at 30 fps, with AAC; the video edit list starts at 42.7 ms.
  - **Content:** a frame of the MP4 shows the same 3-video layout as the recorder preview.
  - **All at once:** video + mix + stems produced 5 files; the WAVs were identical to the 5a ones.
  - **Cleanup:** I deleted the duplicate "(1)" files of that second test. The first `Música 2.mp4` and the 5a WAVs are still on the phone for you to check.

### 5b checklist (owner)
Use a project with 2+ tracks with video, a trim and some layout/background changes. Ideally one with a clap at the start.
1. In the recorder tap **Exportar**. The **Vídeo (MP4)** card shows the size, 30 fps, the bitrate and "Salvo em Movies/Selfband/<projeto>.mp4". Leave only Vídeo on and export.
2. When done, open the MP4 from the gallery (album Selfband) or with **Compartilhar**. It should look exactly like the canvas in the recorder: same layout, crops, corners, border, background (color, blur or picture), only the trimmed range.
3. **Sync:** on the clap, picture and sound happen together. Compare with the preview in the recorder; there should be no extra offset.
4. Turn on **Mix** and **Stems** too and export again: you get the MP4 plus the WAVs, and the done screen lists both folders. Share them all at once.
5. Try a 16:9 project and a 1:1 one: 1920×1080 and 1080×1080.
6. Export a longer project, then press Home or turn the screen off: the notification shows "Renderizando o vídeo…" with progress and the export finishes.
7. Start a long export and **Cancelar**: no partial MP4 is left in Movies/Selfband.
8. Post a test to Instagram/TikTok (optional, per SPEC §5): it should accept the file and keep the sync after their recompression.

### 5b known issues / notes
- 5b had fixed settings (H.264, 1080p, 30 fps, automatic bitrate); 5c adds the choices.
- All videos are decoded at once, one decoder each. Projects with many video tracks may hit the device's decoder limit; multi-pass composition is planned for Phase 6 (SPEC 3.5).
- AAC uses the project sample rate. SPEC asks for 48 kHz; projects are created at the device rate, which is 48 kHz on the test phone. A 44.1 kHz project would export AAC at 44.1 kHz (no resampling yet).
- Exporting again does not overwrite: Android adds " (1)" to the name.

### 5c — Video options: HEVC, 720p/4K, 60 fps, manual bitrate, decoder limits (confirmed by the owner on 2026-11-08)
- **Choices (SPEC 2.7):** on the export screen, with Vídeo (MP4) on:
  - **Codec:** H.264 (default) or HEVC.
  - **Resolução:** 720p, 1080p (default) or 4K; the short side is 720 / 1080 / 2160.
  - **Quadros por segundo:** 30 (default) or 60.
  - **Bitrate:** Automático (default) or Manual, with a slider in 0.5 Mbps steps limited to what the encoder accepts (at most 200 Mbps).
  - A summary line shows the size, bitrate and estimated file size, e.g. "1080×1920 · 12,5 Mbps · cerca de 11 MB".
- **Automatic bitrate:** 0.2 bits per pixel per frame for H.264, and 60 % of that for HEVC.
  - Examples: 1080p30 H.264 12.5 Mbps, HEVC 7.5 Mbps; 4K30 H.264 50 Mbps; 4K60 HEVC 59.5 Mbps.
  - Any bitrate beyond the encoder's range is clamped at encode time.
- **Only what the device can do (SPEC 1, "4K when supported"):** the screen asks MediaCodecList, for the project's canvas shape, which codec × size × frame-rate combinations have an encoder, and each one's bitrate range.
  - A choice no encoder takes is disabled, with a note.
  - When the value just tapped does not combine with the others, it is kept and the others adapt. For example, at 4K30 on a device without 4K60, tapping 60 fps drops the size to 1080p; tapping 4K at 1080p60 drops to 30 fps.
  - On the test phone every combination is available.
- **60 fps note:** if the takes were recorded at a lower rate (30 fps here), the screen warns that frames repeat and the file gets bigger.
- **Decoder limit (SPEC 3.5):** the export decodes every video at once.
  - The screen reads each video's format, finds its decoder and compares the count with `getMaxSupportedInstances()`.
  - If the project needs more, video export is blocked with a clear message (fewer video tracks for now; multi-pass composition is Phase 6).
  - Audio exports are not affected.
- **HEVC files** use the `hvc1` tag, the one Apple devices and the social apps expect.
- **Names:** the done screen and the log now show the name Android actually gave the file (e.g. "Música 2 (1).mp4" when the name was taken), not the requested one.
- 7 new tests: options → spec (HEVC 4K, manual bitrate, 720p60), size estimate, and choosing the nearest supported mode. 88 unit tests in total.
- Checked by me on the phone, project "Música 2" (7.09 s):
  - **HEVC 4K60:** 2160×3840, 426 frames, `hvc1`, 52.1 MB (screen estimate: 53 MB), same 42.7 ms AAC compensation.
  - **H.264 720p, manual 13 Mbps:** 720×1280, 213 frames, 10.9 MB.
  - **Cleanup:** I deleted those test files; `Música 2.mp4` from 5b is still there.
  - **Not tested:** I could not trigger the decoder limit here (3 videos is far below this phone's limit).

### 5c checklist (owner)
1. Open **Exportar**: the Vídeo card shows Codec, Resolução, Quadros por segundo and Bitrate, plus the summary with size and estimated file size. Change each one and watch the summary update.
2. Export **HEVC 4K 60 fps** (Automático). The file plays in the gallery, looks like the preview and stays in sync on the clap. It is much bigger: compare with the estimate.
3. Choose **Manual** bitrate, move the slider, export **720p**. The file size follows the chosen bitrate (roughly Mbps × seconds / 8).
4. Export **H.264 1080p 30** again. It is the same as in 5b.
5. With 60 fps selected, the note about 30 fps takes appears.
6. Optional: share an HEVC file and an H.264 file to Instagram/TikTok and see which they accept. H.264 is the safe default.
7. Optional: try a project with many video tracks. If the phone cannot decode them all at once, the screen says so and the Exportar button stays off while Vídeo is on.

### 5c known issues / notes
- Choices are not remembered between exports yet (configurable defaults are Phase 6, SPEC 2.8).
- 60 fps only makes the export smoother when the takes have 60 fps; the camera records at 30 fps today.
- The decoder check counts instances only. Real throughput (several 4K decodes at once) is measured in Phase 6 together with proxies.
- Phase 5 (end of the MVP) is complete.

## Phase 6 — Scale and experience

### 6a — Preview proxies (confirmed by the owner on 2026-11-08)
- **What a proxy is (SPEC 3.5, 3.6):** a light copy of a take video, `tracks/<trackId>/<takeId>.proxy.mp4`.
  - H.264, short side 540 (never upscaled), ≈ 0.12 bits per pixel (≈ 1.9 Mbps at 540p30), a key frame every 0.25 s so seeking and scrubbing stay quick.
  - Every frame keeps the original's presentation time and the rotation stays metadata, so the proxy shows the same frames at the same moments; the per-take video offset and the layout apply unchanged.
- **Made in the background:** after a take is saved, the proxies of the active takes that lack one are queued.
  - One at a time, on a low-priority thread: decode → draw scaled with the compositor's layer shader → encode → mux into a `.tmp` file, renamed when complete.
  - Paused while recording (the camera's encoder and the disk come first); the proxy in progress is abandoned and redone afterwards.
  - Opening a project also queues missing proxies of older takes.
  - Deleting a take or track deletes its proxy. A failed proxy is not retried until the app restarts; the preview keeps the original.
- **When the preview uses them:** new setting **Configurações → Proxies na prévia**:
  - **Automático** (default): from 8 videos on screen ("~8 tracks"), or when the originals need more simultaneous decoders than the device declares (`getMaxSupportedInstances`), or when the preview **measures** that it cannot keep up (more than 25 % of the frames dropped for two 3-second intervals in a row while frames flow; then proxies until the screen is left).
  - **Sempre** / **Nunca**.
  - Videos without a proxy yet use the original; the preview switches to a proxy as soon as it is finished.
- **Export is unchanged:** it always decodes the originals.
- **Settings screen:** the gear on the projects list now opens **Configurações** (proxies, plus the link to **Diagnóstico de áudio**). Settings are saved in `files/settings.json`; 6c adds the defaults, theme and language there.
- The decoder-instance check moved to `:core:video` (`DecoderInstances`), shared by the preview and the export.
- 8 new tests (proxy size, the automatic rule, settings file). 96 unit tests in total.
- Checked by me on the phone, project "Música 2" (3 video tracks, 1080p):
  - Opening the project queued 3 proxies; each 8 s take took 0.7–0.9 s. 960×540, 1.96 MB against 16.1 MB.
  - Original vs proxy: same 239 frames, **identical frame times** (0 difference), same rotation matrix; 30 key frames against 8.
  - **Sempre:** the log says "proxies on", the canvas shows the 3 layers correctly, and playback runs at 30 frames per second per layer (90 per 3 s), with only a few frames skipped right after start.
  - **Nunca** and **Automático** saved and applied. With 3 videos, Automático keeps the originals.
  - A deleted proxy is made again when the project is reopened.
  - I left the setting on **Automático**.
  - **Not tested by me:** the pause during recording (I did not record on your phone), and the automatic switch with 8+ videos or measured slowness (that comes with 6b's big test project).

### 6a checklist (owner)
1. Open a project with video takes and wait a few seconds. In **Configurações**, "Gerando N proxies…" shows while they are being made and disappears when done.
2. Set **Proxies na prévia → Sempre** and go back to the project. The preview looks the same, only a little softer; play it and check that the videos stay in sync with the audio (the clap) exactly as before.
3. Scrub the timeline back and forth with **Sempre**: frames follow the finger at least as well as before.
4. Record a new take with **Sempre**: during recording nothing is generated; a few seconds after stopping, its proxy appears and the preview switches to it (slightly softer).
5. Set **Nunca**: the preview goes back to the originals (sharper).
6. Leave **Automático**: with fewer than 8 videos the preview uses the originals.
7. Export a video: the file is full quality (the export never uses proxies).
8. Delete a take: its proxy goes too (nothing to see; I check the files).

### 6a known issues / notes
- With fewer than 8 videos on this phone the automatic mode keeps the originals; the real test of the automatic switch is 6b's 12–16 track project.
- Proxies take disk space (≈ 14 MB per minute per track at 540p30).
- The speed measurement is per screen: leaving and reopening the project measures again.

### 6b — Multi-pass export and the 12–16 track test (confirmed by the owner on 2026-11-08)
- **Multi-pass composition (SPEC 3.5):** when a project has more videos than the device decodes at once, the video export now composes in passes instead of being blocked.
  - The layers, bottom to top, are split into groups.
  - Pass 1 draws the background (color, blur or picture) and the first group into an intermediate video.
  - Each later pass draws the previous intermediate over the whole canvas, then its own group.
  - The last pass is the final MP4, with the audio.
  - Painting bottom to top like this gives the same picture as a single pass; the intermediates need no transparency.
  - Intermediates: same codec, size and frame rate as the output, at 2.5× its bitrate, video only, frame k stamped exactly k / fps so the next pass picks exactly frame k.
  - They live in the app cache (`export-passes/`); each is deleted as soon as the next pass is done, and the folder is emptied at the end, on failure and on cancel.
- **Decoders per pass:** what the decoders declare (`getMaxSupportedInstances`): the fewest among the originals' decoder and the output codec's decoder, since later passes also decode an intermediate.
  - The first pass also counts the blurred background's video when it is not one of its layers; every later pass keeps one decoder for the intermediate.
  - Example: 16 videos with 6 decoders → passes of 6, 5 + intermediate, 5 + intermediate.
  - **If a decoder refuses to start** although the codec declared room (memory or hardware limits), the export starts the video over with fewer decoders per pass (as many as did start), up to 4 times. Only below 2 decoders does it fail.
- **New setting — Configurações → Vídeos por passada na exportação:** Automático (default), 8 or 4. A fixed value forces passes on any project with more videos than that; useful to lower when a big project fails, and to try multi-pass on this phone.
- **Export screen:** the hard block is gone. With more than one pass it notes "o vídeo será composto em N passadas (mais lento)"; it only blocks when fewer than 2 videos can be decoded at once. While running, the screen and the notification show "Renderizando o vídeo (passada 2 de 3)…". Progress counts all passes.
- **12–16 track test project:** I created **"Teste 16 trilhas"** on the phone by copying the 3 tracks of "Música 2" into 16 tracks (same takes repeated, each at −12 dB so the sum does not clip; automatic 4×4 grid). It is a test project of mine: delete it after the checklist, or tell me to.
- 6 new tests (pass split: all in one pass, one decoder kept for the intermediate, separate blur source, minimum of 2, every layer drawn once in order, the pass scene). 102 unit tests in total.
- Checked by the owner on the phone (checklist below); I installed the APK and created the test project without opening the app.

### 6b checklist (owner)
1. Open **Teste 16 trilhas**. The preview shows the 16 videos in a 4×4 grid; with **Proxies na prévia → Automático** it switches to proxies (16 ≥ 8) once they are made, about 15 s. Play it: it should be smooth and in sync.
2. **Exportar** with Vídeo (1080p H.264 30 fps). On this phone it will probably fit in one pass (no note on the screen). The file plays, shows all 16 videos and stays in sync on the clap.
3. In **Configurações → Vídeos por passada na exportação** choose **4**. Back in **Exportar**, the note says "5 passadas". Export: the screen shows "passada 1 de 5" … "5 de 5", and the file must look exactly like the one from step 2 (same layout, sync, background).
4. Try **8** (3 passes) and compare again. With the blurred background (Layout → Fundo → Desfoque), the export with passes also looks like the single-pass one.
5. Cancel an export with passes in the middle: it stops, nothing is left in Movies/Selfband.
6. Set the option back to **Automático**.
7. "Música 2" (3 videos) exports exactly as before, in one pass.
8. Delete **Teste 16 trilhas** (or ask me to).

### 6b known issues / notes
- Each pass re-encodes; at 2.5× the bitrate the loss should not be visible, but a 5-pass export is slower (roughly 5× the video time) and uses temporary space in the cache (about 2.5× the final video per intermediate, two at a time at most).
- The retry with fewer decoders only covers decoders that fail to start; one that starts and then stalls is caught by the existing 2 s frame timeout (the frame is skipped and logged).
- Automático trusts what the codecs declare; on this phone that is probably more than 16, so the real multi-pass path is exercised with the 4 / 8 setting.

### 6c — Settings: defaults, theme, language, verbose log (owner said to proceed on 2026-11-08)
- **Settings screen** (Projects → ⚙) now has sections: Aparência, Novos projetos, Exportação, Proxies, Vídeos por passada, Diagnóstico.
- **Theme**: Sistema / Claro / Escuro, applied app-wide with `AppCompatDelegate.setDefaultNightMode` from `SelfbandApplication` (read at startup, then followed live).
- **Language**: Sistema / Português (Brasil) / English through the per-app language API (`AppCompatDelegate.setApplicationLocales`).
  - On Android 13+ it is the same setting as System → Apps → Selfband → Idioma.
  - The locale config is generated by AGP (`generateLocaleConfig`).
  - This required `androidx.appcompat` 1.7.1 (Apache-2.0); `MainActivity` is now an `AppCompatActivity` with an AppCompat DayNight theme.
- **New-project defaults**: format, camera (lens, resolution, fps, stabilization) and metronome (on/off, BPM, time signature, count-in, click sound). They are applied when a project is created (`AppSettings.applyTo`); existing projects are untouched.
- **Export defaults**: video on/off, codec, resolution, fps, mix WAV, stems, WAV format. The export screen starts with these, then fits them to what the device's encoder supports.
- **Export folder**: name used as `Movies/<name>/` and `Music/<name>/<project>/`. It is sanitized: no `/ \ : * ? " < > |`, at most 40 characters, `Selfband` if empty.
- **Detailed log** toggle: debug-level lines in logcat (the preview's per-second stats, export per pass, proxy start). Off by default.
- **Audio device kinds** shown in the recorder (output/input and the route-change notice) are now translated ("Alto-falante · …", "Interface USB · …"). The Diagnostics screen keeps the raw technical names.
- `VideoCodec` moved from `:core:export` to `:core:model` (it is now part of the settings).
- New tests: `AppSettingsTest` (applyTo, clamping, folder sanitizing, reading old settings files) and `ExportOptionsTest`. 110 unit tests, 0 failures.

### 6c checklist (owner)
1. Configurações → **Tema**: Claro, then Escuro, then Sistema. The whole app (projects, recorder, export) follows the choice right away, and after closing and reopening the app.
2. **Idioma**: English → the app text switches to English; Português (Brasil) → back to Portuguese; Sistema → follows the phone. Android 13+: System → Apps → Selfband → Idioma shows the same choice.
3. **Novos projetos**: pick 16:9, camera Traseira, 720p, 60 fps, metrônomo ligado at 90 BPM, 6/8, 2-bar count-in, Bloco de madeira. Create a project → it opens with all of these. An older project keeps its own settings.
4. **Exportação**: pick HEVC, 720p, Mixagem em WAV on, 32 bits float. Open Export on a project → it starts with these choices (anything the device cannot encode is adjusted, as before).
5. **Pasta de exportação**: type a name such as `Minha Banda` and export → files appear in `Movies/Minha Banda/` and `Music/Minha Banda/<projeto>/`. A name with `/` or `:` is shown with `_` in the hint below the field.
6. **Log detalhado** on → `adb logcat -s Selfband:V` shows the preview's `D` lines while playing; off → they stop.
7. In the recorder, the output/input lines show the device kind in the app language (e.g. "Alto-falante", "Interface USB"), and switch language with the app.

### 6c known issues / notes
- Changing the language recreates the screen (that is how Android applies it); an unsaved text field on that screen may lose its draft.
- The defaults only apply to projects created after the change, as SPEC 2.8 describes; there is no "apply to existing projects".
- Not checked on the phone by me: the owner was using another app, so the APK was only installed (not opened). Logcat review is part of the checklist.
