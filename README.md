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
| Bluetooth microphone/audio test independent of a SIM call | Implemented for diagnostics only |
| Experimental HFP Hands-Free client probe on the remote | Implemented; depends on OEM/system support |
| Digital two-way cellular-call audio through HFP | **Experimental; not verified on target devices** |

## Experimental HFP cellular-audio architecture

The remote now probes for Android's hidden/system-level HFP Hands-Free client profile and attempts to connect to the selected gateway. If the profile is exposed and permitted by the OEM firmware, VGS requests the HFP audio connection when the gateway reports an active cellular call. The gateway continues using Android's normal Bluetooth/Telecom Audio Gateway role; the existing RFCOMM link remains responsible for contacts, notifications and companion commands.

The HFP client role is not part of the ordinary public Android app API on many Android builds. The probe uses reflection so VGS can compile against the public SDK, but reflection does not grant privileged permissions or add a missing system Bluetooth profile. The Huawei may reject the profile, and a successful profile connection still needs real-call testing in both directions.

The existing `AudioBridge` remains a diagnostic microphone/audio transport only. It is not used as a cellular-call audio path, and VGS does not use acoustic speaker-to-microphone relay.

VGS is **not yet verified as a complete remote-handset solution**. Test the HFP status shown on the remote and check logs for profile availability, connection and audio-control results. If the OEM blocks the HFP client profile, an ordinary app cannot reliably work around that restriction on stock firmware.

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


## Experimental APK build

The experimental HFP branch has a separate `experimental` build type. GitHub Actions builds it as `VGS Experimental` with application ID `com.vgs.app.experimental`, so it can be installed alongside the regular VGS app. The build is unsigned by the release key and is intended for testing only; download the `VGS-Experimental` artifact from the **Build VGS Experimental APK** workflow.
