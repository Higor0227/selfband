<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<img src="docs/images/icon-512.png" alt="Selfband icon" width="96" align="right">

# Selfband

**Record synchronized multitrack videos by yourself: a one-person band on your Android phone.**

Record a base track, then record the other parts while listening to the previous ones, and assemble everything into a video collage with real-time mixing and editing.

> Status: feature-complete for a first release (0.x), being tested on real devices. Development status: [PROGRESS.md](PROGRESS.md).

## Features

- **Recording:** each track is recorded while the others play back, with front or back camera, metronome and count-in, takes, and audio-only tracks.
- **Sync:** latency calibration per device and audio route; the audio clock leads and video follows.
- **Canvas:** 9:16, 16:9 and 1:1, ready-made layouts, a free layout editor and your own templates.
- **Mixer:** volume, pan, mute/solo, meters, EQ per track, reverb send, master limiter, offset by dragging the waveform, global trim.
- **Export:** MP4 (H.264 or HEVC, 720p to 4K, 30/60 fps), WAV mix and dry stems, saved to the gallery and ready to share.
- **Languages:** English and Brazilian Portuguese.

## Install

Signed APKs are published on the [Releases](https://github.com/higor0227/selfband/releases) page. F-Droid and Play Store are planned ([F-Droid evaluation](docs/FDROID.md)).

Privacy: Selfband has no internet permission and collects no data. See the [privacy policy](docs/PRIVACY.md) ([português](docs/PRIVACY.pt-BR.md)).

## Goals

- **Accurate sync.** Per-device latency compensation with calibration. Target: tracks aligned within ±1 ms.
- **No artificial limits.** Number of tracks and duration are limited only by hardware and storage.
- **Works with any gear.** The phone mic, wired headphones, Bluetooth (with warnings), or any USB class-compliant audio interface. Nothing in the app is tailored to a specific brand or model: behavior comes from what is detected, what is measured, or what the user chooses.
- **Focused.** Two audio effects only, EQ and reverb. It is not a full DAW.
- **Private.** No internet permission, no data collection.

The full product and technical specification is in [SPEC.md](SPEC.md) (Portuguese). Store texts and images are in `fastlane/metadata/android`.

## Building

Requirements:

- Android Studio (recent stable), or JDK 17+ with the Android SDK
- Android SDK Platform 37; NDK and CMake are installed automatically by the Android Gradle Plugin on the first build

```bash
./gradlew assembleDebug      # build
./gradlew installDebug       # install on a connected device
./gradlew test               # unit tests
adb logcat -s Selfband:V     # app logs
```

Minimum Android version: 10 (API 29).

Release builds are minified and signed by GitHub Actions when a version tag is pushed; see [docs/RELEASING.md](docs/RELEASING.md).

### Testing with a USB audio interface

The phone's USB-C port is usually taken by the audio interface, so use wireless debugging (Android 11+):

```bash
adb pair <ip>:<pairing-port>     # from "Pair device with pairing code"
adb connect <ip>:<port>          # from the main "Wireless debugging" screen
```

## Architecture

Kotlin + Jetpack Compose UI, a C++ audio engine on [Oboe](https://github.com/google/oboe) (AAudio, with OpenSL ES fallback), and Hilt. The audio frame position is the master clock; video follows audio.

| Module | Purpose |
|---|---|
| `:app` | Entry point, navigation, DI root |
| `:core:model` | Pure Kotlin models |
| `:core:project` | Project persistence, versioned schema |
| `:core:audio` | C++ engine (Oboe) + Kotlin API |
| `:core:camera` | Video capture |
| `:core:video` | Decoders, proxies, GL compositor |
| `:core:export` | Offline render and export |
| `:core:ui` | Design system and shared Compose components |
| `:feature:*` | Screens. They depend only on `core` modules, never on each other |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Selfband is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).
