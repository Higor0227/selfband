<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Selfband privacy policy

*Effective date: 2026-11-08* · [Português](PRIVACY.pt-BR.md)

Selfband is a free, open-source app for recording multitrack videos on Android. It is developed
by Higor (github.com/higor0227) and its source code is public at
https://github.com/higor0227/selfband.

## Summary

**Selfband does not collect, send or share any data.** The app does not have the Android
internet permission, so it cannot connect to any server. There are no ads, analytics, crash
reporters, accounts or third-party SDKs that collect data.

## What the app uses, and why

| Permission | Used for |
|---|---|
| Microphone (`RECORD_AUDIO`) | Recording audio tracks and measuring audio latency (calibration). |
| Camera (`CAMERA`) | Recording the video of each track. |
| Notifications (`POST_NOTIFICATIONS`) | Showing the progress of an export while it runs in the background. |
| Foreground service | Finishing an export when you leave the app. |

The microphone and the camera are used only while you are on a recording or calibration screen,
and Android shows its usual indicators while they are on.

## Data stored on your device

Everything the app creates stays on your phone:

- your projects (audio and video recordings, mix and layout settings), in the app's private
  storage;
- calibration results for your audio devices (latency measurements), and your settings and saved
  layout templates;
- exported videos and audio files, saved in `Movies` and `Music`, in a folder you can name
  (by default `Selfband`), where
  other apps can see them, like any other video on your phone.

The app opts out of Android cloud backup, so none of this is copied to Google servers by the app.
Uninstalling the app deletes its private data. Exported files stay until you delete them.

## Sharing

When you tap "Share" after an export, Android's share sheet opens and **you** choose the app that
receives the file (for example Instagram or TikTok). From then on, that app's privacy policy
applies to that file. Selfband never sends anything by itself.

## Children

The app does not collect personal data from anyone, including children.

## Changes

If this policy changes, the new version will be published at this address with a new effective
date, and the change will be recorded in the project's public history.

## Contact

Questions about this policy: open an issue at https://github.com/higor0227/selfband/issues.
