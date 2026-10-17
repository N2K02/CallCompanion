# ShizuCallCompanion

[![Latest release](https://img.shields.io/github/v/release/N2K02/ShizuCallCompanion?label=download)](https://github.com/N2K02/ShizuCallCompanion/releases/latest) ![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Use an Android phone without a SIM as a Bluetooth companion for a second phone that has a SIM.

ShizuCallCompanion lets the remote phone display call notifications and request calls through the gateway phone. It works over Bluetooth and does not require mobile data or internet for the phone-to-phone connection.

**[Download the latest APK](https://github.com/N2K02/ShizuCallCompanion/releases/latest)** · Android 8.0+ · Free and open source

Install ShizuCallCompanion on both phones and choose a role:

- **Gateway:** the phone with the SIM. It places calls and reports cellular call state.
- **Remote:** the phone without a SIM. It shows calls, contacts and notifications and sends call commands to the gateway.

## Features

| Feature | Status |
|---|---|
| Incoming SIM call notification on the remote | Implemented |
| Answer, reject and hang up from the remote | Implemented |
| Dial a number from the remote using the gateway's SIM | Implemented |
| Sync gateway contacts to the remote | Implemented |
| Mirror gateway notifications to the remote | Implemented |
| Bluetooth microphone/audio test independent of a SIM call | Implemented for diagnostics |
| Use the remote microphone as the cellular caller's microphone | **Experimental Shizuku prototype; requires device testing** |
| Send the other party's cellular-call audio digitally to the remote | **Experimental Shizuku downlink capture; requires device testing** |

## Known limitations — cellular call audio

ShizuCallCompanion currently has two separate cellular-audio limitations. The call-control features can work, but these limitations mean ShizuCallCompanion is **not yet a complete remote-handset solution for talking through the gateway SIM**.

### 1. Remote voice → cellular caller

The remote microphone can be streamed over the Bluetooth voice link, but Android does not provide ordinary third-party apps with a supported way to inject that audio directly into a SIM call's outgoing microphone/uplink. The gateway speaker-and-microphone acoustic workaround has been removed; ShizuCallCompanion no longer automatically turns on the gateway loudspeaker to relay remote audio.

### 2. Cellular caller → remote

Ordinary Android apps cannot freely capture both sides of a cellular call. The `VOICE_CALL` audio source requires the privileged `CAPTURE_AUDIO_OUTPUT` permission, and whether a privileged helper can access the call audio depends on the device's Android build and audio implementation.

ShizuCallCompanion now includes experimental Shizuku-based paths in both directions. The gateway can attempt to inject the remote microphone into the cellular uplink and capture the caller's downlink using Android's hidden call-redirection audio APIs. Capture starts only when the remote user enables the voice link during an active SIM call; it stops when voice is disabled, the Bluetooth link disconnects, or the call ends. The PCM is forwarded over the existing Bluetooth connection and is not saved to disk. Neither direction is verified on the target gateway yet: Android version, OEM audio policy, Shizuku identity/permissions, and carrier implementation can all affect support. ShizuCallCompanion deliberately does not fall back to playing the remote microphone through the gateway speaker. The ordinary microphone/Bluetooth test alone does not prove cellular call audio works.

## Install and test

1. Download the latest APK from [Releases](https://github.com/N2K02/ShizuCallCompanion/releases/latest) and install it on both phones.
2. Pair the phones in Android **Settings → Bluetooth**.
3. On the gateway, open ShizuCallCompanion, choose **Gateway**, press **Start**, and grant the requested permissions.
4. On the remote, open ShizuCallCompanion, choose **Remote**, select the paired gateway, press **Start**, and grant the requested permissions.
5. Confirm the status says **Connected**.
6. For the experimental cellular-audio test, start Shizuku on the gateway, grant ShizuCallCompanion access in the gateway setup checklist, place or answer a SIM call, then enable the voice link on the remote. Check both directions: ask the other caller whether they hear the remote microphone, and confirm whether the remote hears the caller. Capture/injection failures are recorded in the ShizuCallCompanion log. These paths are experimental and may not work on every phone.

On some phones, set ShizuCallCompanion battery usage to **Unrestricted** so Android is less likely to stop it in the background.

## Updates

ShizuCallCompanion checks GitHub Releases for updates when opened (at most every 12 hours). If a newer release is available, the app offers the update. Android's package installer asks you to confirm installation.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes a release named "ShizuCallCompanion <version>". Locally, run `./gradlew assembleRelease` with JDK 17 and the Android SDK installed.

## License

MIT. See [LICENSE](LICENSE).
