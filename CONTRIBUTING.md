# Contributing to Selfband

Thanks for your interest! Selfband is GPL-3.0-or-later. By contributing you agree that your contributions are licensed under the same terms.

## Ground rules

- **English** for code, comments, commit messages, and documentation. UI text goes in string resources, in both English (`values/`) and Brazilian Portuguese (`values-pt-rBR/`).
- **No device-specific behavior.** Never branch on brand, model, manufacturer, or USB vendor/product IDs. If some hardware behaves differently, solve it with a generic mechanism: detection, measurement (calibration), or a user setting.
- **Real-time safety.** Code that runs on the audio callback thread must not allocate memory, take locks, log, do I/O, or call into the JVM. Use the lock-free ring buffers and atomic parameters provided by the engine.
- **Module boundaries.** `:feature:*` modules depend only on `:core:*` modules, never on another feature (the build enforces this). Core modules expose interfaces.
- **Licenses.** Only add dependencies with GPL-3.0-compatible licenses (Apache-2.0, MIT, BSD, LGPL, MPL-2.0). Mention the license in the pull request.
- **SPDX headers.** Every source file starts with `SPDX-License-Identifier: GPL-3.0-or-later`.
- **No network.** The app has no internet permission and collects no data.

## Workflow

1. Open an issue to discuss larger changes first.
2. Keep pull requests focused. Include tests for DSP, alignment math, serialization, and layout math.
3. Run `./gradlew test assembleDebug` before pushing.
4. For audio changes, report which setup you tested (built-in mic, wired headphones, Bluetooth, USB interface) and the xrun count from the diagnostics screen.

## Commit messages

Imperative mood, short subject line (for example `Add loopback calibration`), with an optional body explaining why.
