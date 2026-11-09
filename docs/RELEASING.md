<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Releasing Selfband

Release APKs and app bundles are built and signed by GitHub Actions
(`.github/workflows/android.yml`, job `release`) when a tag `v<versionName>` is pushed.
The signing key never goes into the repository: the workflow reads it from GitHub Secrets.

## One-time setup

### 1. Create the release key

Run this on your own computer (PowerShell; `keytool` comes with Android Studio). It asks for a
password and your name; choose a strong password and keep it in a password manager.

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore selfband-release.jks -alias selfband -keyalg RSA -keysize 4096 -validity 10000
```

Keep `selfband-release.jks` and its password **backed up outside the repository**
(`*.jks` is in `.gitignore`). Every update of the app must be signed with the same key:

- **GitHub / F-Droid builds signed by you:** if the key is lost, users have to uninstall
  (and lose their data) to install a new version.
- **Play Store:** enroll in Play App Signing. Google then keeps the app signing key, and this
  key is only the *upload key*, which Google can reset if it is lost.

### 2. Register the GitHub Secrets

In the repository: Settings → Secrets and variables → Actions → New repository secret.

| Secret | Value |
|---|---|
| `SELFBAND_KEYSTORE_BASE64` | the `.jks` file in base64 (command below) |
| `SELFBAND_KEYSTORE_PASSWORD` | the keystore password |
| `SELFBAND_KEY_ALIAS` | `selfband` (the `-alias` above) |
| `SELFBAND_KEY_PASSWORD` | the key password (by default, the same as the keystore password) |

To copy the base64 of the key to the clipboard (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("selfband-release.jks")) | Set-Clipboard
```

## Making a release

1. In `app/build.gradle.kts`, raise `versionName` and `versionCode`
   (`versionCode = major * 10000 + minor * 100 + patch`; for example 0.1.0 → 100, 1.2.3 → 10203).
   The `versionCode` must always increase.
2. Commit, then tag and push:
   ```powershell
   git tag v0.1.0
   git push origin main --tags
   ```
3. The `release` job checks that the tag matches `versionName`, builds the signed APK and AAB,
   and creates a **draft** GitHub release with the APK and `SHA256SUMS`.
4. On GitHub, open Releases, review the draft (notes, APK), and publish it.
   The `.aab` (for the Play Console) is in the workflow run's artifacts.

## Local builds

- `./gradlew assembleRelease` without a key gives an **unsigned** minified APK
  (this is also what F-Droid builds and then signs with its own key).
- To test the minified build on a phone that has the debug build installed, sign it with the
  debug key: `./gradlew assembleRelease -Pselfband.debugSignedRelease=true`. It installs over
  the debug build without losing data. Never distribute this APK.
- To sign locally with the real key, set the same four variables in the environment, with
  `SELFBAND_KEYSTORE_FILE` (a path to the `.jks` file) in place of `SELFBAND_KEYSTORE_BASE64`.
