# VGS

[![Latest release](https://img.shields.io/github/v/release/N2K02/VGS?label=download)](https://github.com/N2K02/VGS/releases/latest) ![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Use an Android phone without a SIM as a Bluetooth companion for a second phone that has a SIM.

VGS lets the remote phone display call notifications and request calls through the gateway phone. It works over Bluetooth and does not require mobile data or internet for the phone-to-phone connection.

**[Download the latest APK](https://github.com/N2K02/VGS/releases/latest)** · Android 8.0+ · Free and open source

Install VGS on both phones and choose a role:

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
| Use the remote microphone as the cellular caller's microphone | **Not implemented — needs further investigation** |
| Send the other party's cellular-call audio digitally to the remote | **Not implemented — needs further investigation** |

## Known limitations — cellular call audio

VGS currently has two separate cellular-audio limitations. The call-control features can work, but these limitations mean VGS is **not yet a complete remote-handset solution for talking through the gateway SIM**.

### 1. Remote voice → cellular caller

The remote microphone can be streamed over the Bluetooth voice link, but Android does not provide ordinary third-party apps with a supported way to inject that audio directly into a SIM call's outgoing microphone/uplink. The gateway speaker-and-microphone acoustic workaround has been removed; VGS no longer automatically turns on the gateway loudspeaker to relay remote audio.

### 2. Cellular caller → remote

Ordinary Android apps cannot freely capture both sides of a cellular call. The `VOICE_CALL` audio source requires the privileged `CAPTURE_AUDIO_OUTPUT` permission, and whether a privileged helper can access the call audio depends on the device's Android build and audio implementation.

Both directions need further investigation on the target gateway phone. The standalone microphone/Bluetooth test only verifies the phone-to-phone audio transport; it does not prove that audio can be injected into or captured from a cellular call.

## Install and test

1. Download the latest APK from [Releases](https://github.com/N2K02/VGS/releases/latest) and install it on both phones.
2. Pair the phones in Android **Settings → Bluetooth**.
3. On the gateway, open VGS, choose **Gateway**, press **Start**, and grant the requested permissions.
4. On the remote, open VGS, choose **Remote**, select the paired gateway, press **Start**, and grant the requested permissions.
5. Confirm the status says **Connected**.
6. Test call notifications and remote dialing. For the Bluetooth audio transport only, use **Microphone / Bluetooth audio test** on the remote. This test does not connect its audio to a cellular call.

On some phones, set VGS battery usage to **Unrestricted** so Android is less likely to stop it in the background.

## Updates

VGS checks GitHub Releases for updates when opened (at most every 12 hours). If a newer release is available, the app offers the update. Android's package installer asks you to confirm installation.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes a release named "VGS <version>". Locally, run `./gradlew assembleRelease` with JDK 17 and the Android SDK installed.

## License

MIT. See [LICENSE](LICENSE).
