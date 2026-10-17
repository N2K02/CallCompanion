# CallCompanion

Use an Android phone **without internet** like a smartwatch for a phone that **has a SIM / internet**, over Bluetooth.

Install the same app on both phones and pick a role:

- **Gateway**: the phone with the SIM / internet.
- **Remote**: the "watch-mode" phone with no internet.

## What works

| Feature | How |
|---|---|
| Incoming SIM call rings on the remote (name + number) | Gateway watches call state and forwards it over Bluetooth |
| Answer / reject / hang up from the remote | Gateway answers / ends the call via Android Telecom |
| Dial a number from the remote | Gateway places the call |
| Two-way voice between the phones | Live 8 kHz audio over the Bluetooth link ("Voice link") |
| Turn gateway loudspeaker on/off from the remote | Remote command |

### Known limitation

Android does **not** let a normal app capture or inject the audio of a SIM call. So when you answer a SIM call from the remote, the conversation itself still happens on the gateway phone (use its loudspeaker, or a Bluetooth headset on it). Relaying the actual call audio needs root / a system app, or a VoIP (internet) calling setup. The voice link in this app is the audio-relay foundation for that next step.

## Install & test

1. Download `CallCompanion.apk` from the latest **Release** of this repo and install it on **both** phones (allow "install unknown apps").
2. Pair the two phones in Android **Settings → Bluetooth**.
3. **Gateway phone:** open CallCompanion → choose *Gateway* → **Start** → allow all permissions (Phone, Microphone, Nearby devices, Notifications, Contacts, Call logs).
4. **Remote phone:** open CallCompanion → choose *Remote* → pick the gateway phone from the list → **Start** → allow permissions.
5. Status should turn green: *Connected to …*.
6. Call the gateway's number from a third phone. The remote rings with Answer / Reject.

Tip: on some phones (Xiaomi, Samsung, Huawei) set CallCompanion battery usage to **Unrestricted** so it keeps running in the background.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes it as a release. Locally: `./gradlew assembleDebug` (needs JDK 17 + Android SDK).
