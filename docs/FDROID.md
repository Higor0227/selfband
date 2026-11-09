<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# F-Droid evaluation

Evaluated on 2026-11-08 for version 0.0.1. Summary: **Selfband fits F-Droid's inclusion policy.**
Nothing in the code has to change. Two build-server details need checking when the app is
submitted: JDK 21 and NDK r30.

## Inclusion policy

| Requirement | Status |
|---|---|
| FOSS license | GPL-3.0-or-later, an SPDX header in every source file. |
| All dependencies FOSS | AndroidX, Jetpack Compose, Material 3, Navigation, Hilt/Dagger, kotlinx.coroutines, kotlinx.serialization: Apache-2.0. Oboe: Apache-2.0. No Google Play Services, Firebase, ML Kit or other proprietary SDK. |
| Dependencies from trusted repositories | Only `google()` and `mavenCentral()` (`settings.gradle.kts`). The Gradle plugins also use `gradlePluginPortal()`. |
| No binaries in the source tree | Only `gradle/wrapper/gradle-wrapper.jar`, which F-Droid accepts and checks. |
| No tracking or ads | None. No internet permission, so nothing can be sent. |
| Dependency metadata | Off in the APK (`dependenciesInfo.includeInApk = false` in `app/build.gradle.kts`), as F-Droid requires. It stays on in the Play bundle. |
| Unsigned release build | `./gradlew assembleRelease` without the signing variables gives `app-release-unsigned.apk`, which F-Droid signs. |
| Store metadata | `fastlane/metadata/android/{en-US,pt-BR}`, which F-Droid reads directly (title, descriptions, icon, feature graphic, changelogs by `versionCode`). |

**Anti-features:** none apply (no ads, tracking, non-free network services, non-free
dependencies or non-free assets).

## Points to check when submitting

1. **JDK 21.** The build uses AGP 9.5 and Gradle 9.9, which need JDK 21. The F-Droid build
   server image may come with JDK 17; the recipe can install 21 (`sudo:` step below).
2. **NDK r30 (`30.0.16248370`, pinned in `gradle/libs.versions.toml`).** fdroidserver downloads
   NDKs it knows by version. If r30 is not in its list yet, either wait for it or use an NDK it
   already has (the C++ code only needs C++20 and Oboe's prefab package).
3. **Oboe as a prefab AAR.** Oboe comes from Google Maven as a prebuilt AAR with native
   libraries, like the AndroidX libraries. F-Droid generally accepts FOSS artifacts from Google
   Maven. If the reviewers ask for it to be built from source, Oboe can be added as a git
   submodule and built with `add_subdirectory` in `core/audio/src/main/cpp/CMakeLists.txt`.
4. **Reproducible builds (optional).** With a reproducible build, F-Droid can ship the APK signed
   by the developer, so users can move between F-Droid and GitHub builds without uninstalling.
   Native code makes this harder (build paths in the `.so` files). This is not needed for
   inclusion; F-Droid can sign with its own key.

## Draft recipe for fdroiddata

To submit, open a merge request at https://gitlab.com/fdroid/fdroiddata adding
`metadata/io.github.higor0227.selfband.yml`. Adjust `versionName`, `versionCode` and `commit`
to the first tagged release:

```yaml
Categories:
  - Multimedia
License: GPL-3.0-or-later
SourceCode: https://github.com/higor0227/selfband
IssueTracker: https://github.com/higor0227/selfband/issues
Changelog: https://github.com/higor0227/selfband/releases

AutoName: Selfband

RepoType: git
Repo: https://github.com/higor0227/selfband.git

Builds:
  - versionName: 0.1.0
    versionCode: 100
    commit: v0.1.0
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y -t bookworm-backports openjdk-21-jdk-headless
      - update-java-alternatives -a
    gradle:
      - yes
    ndk: 30.0.16248370

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0
CurrentVersionCode: 100
```

`UpdateCheckMode: Tags` follows the `v*` tags that the release workflow also uses
(see [RELEASING.md](RELEASING.md)), and reads `versionName` and `versionCode` from
`app/build.gradle.kts`.
