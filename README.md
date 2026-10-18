# CallCompanion

Use a second Android phone as a Bluetooth remote for a Gateway phone that has the SIM and remains connected to a Bluetooth hands-free speakerphone. Open Headunit is not required.

Install the same app on both phones and select a role:

- **Gateway**: phone with the SIM and Bluetooth speakerphone. It monitors SIM call state and mirrors third-party notifications.
- **Remote**: companion phone. It displays mirrored notifications and sends call-control requests over Bluetooth.

## Features

| Feature | Behaviour |
|---|---|
| Incoming SIM call state | Gateway forwards ringing state and caller number when Android provides it; contact names can be resolved if Contacts permission is granted. |
| Outgoing SIM call state | Gateway reports an outgoing-call transition when it can infer it from telephony state. Android may not expose the dialled number to a normal app. |
| Answer/reject/end SIM calls | Remote sends a command to the Gateway. Actual success depends on Android/OEM telephony permissions and API behaviour; a normal app cannot guarantee all call controls on every device. |
| Third-party call notifications | With Notification Access enabled on the Gateway, supported call notifications from apps such as WhatsApp can be mirrored to the Remote. |
| Third-party call actions | If the originating app exposes notification action buttons (for example, Answer or Decline), CallCompanion mirrors up to three available actions and asks the Gateway to invoke the original action. Not every app exposes usable actions. |
| Other notifications | Non-ongoing notifications can be mirrored to the Remote. Persistent non-call notifications are ignored. |
| Bluetooth transport | Call state, notification details, and commands travel over the existing Bluetooth RFCOMM connection. |
| Call audio | The Gateway remains paired to the Bluetooth speakerphone, so the conversation audio stays between the Gateway and that device. CallCompanion does not relay cellular-call audio to the Remote. |

## How the call flow works

```text
Remote phone (screen and buttons)
        ⇅ Bluetooth RFCOMM
Gateway phone (SIM, mobile data/Wi-Fi, CallCompanion)
        ⇄ Bluetooth hands-free speakerphone (call microphone + speaker)
```

### Incoming or outgoing SIM call

1. Android on the Gateway reports the call state to CallCompanion.
2. CallCompanion sends the state and available caller details to the Remote over the paired-phone Bluetooth link.
3. Tap **Answer**, **Reject**, or **End** on the Remote. The Gateway receives the request and asks Android Telecom to perform it.
4. The Gateway's Bluetooth speakerphone handles the actual conversation audio. The Remote is a control screen, not the call's audio device.

### Third-party app call (for example, WhatsApp)

1. The third-party app posts a call notification on the Gateway.
2. After Notification Access is enabled, CallCompanion forwards the notification and up to three actions exposed by that app.
3. Tap an action on the Remote. CallCompanion asks the Gateway to invoke the original notification action.
4. The third-party app decides whether that action succeeds. If it does not expose an Answer/Decline action in its notification, CallCompanion cannot manufacture one.

The phones communicate locally over Bluetooth; no CallCompanion cloud server is involved. The Gateway itself still needs whatever internet connection the third-party calling app requires. This app mirrors call status and controls only—it does not forward call audio.

## Android compatibility and setup

The app's minimum supported Android version is Android 8.0 (API 26), so the monitoring and Bluetooth flow covers Android 10 and newer. Android 12+ requires the Nearby devices/Bluetooth Connect runtime permission. Android 13+ also requires notification permission for CallCompanion's own notifications. Microphone permission is optional and is requested only for the separate app voice-link feature; it is not needed for SIM call status or remote call controls.

1. Install the same APK on both phones.
2. Pair the two phones in Android **Settings → Bluetooth**.
3. Pair the Gateway phone with your Bluetooth speakerphone.
4. On the Gateway, choose **Gateway**, grant Phone and Nearby devices permissions, and enable **Notification access** in Android settings.
5. On the Remote, choose **Remote**, select the paired Gateway, and start the connection.
6. Keep CallCompanion running on both phones. On Xiaomi/Redmi/POCO, Huawei/Honor, OPPO/realme/OnePlus, vivo, and some Samsung builds, set CallCompanion battery usage to **Unrestricted** / **Allow background activity**, allow auto-start if offered, and lock the app in Recents if available. Menu names vary. Enable notifications for the system Phone app if you want the call-notification fallback.

## Important Android limitations

- NotificationListenerService requires user-granted Notification Access. It can invoke only actions supplied by the originating notification; it cannot universally answer or end every third-party app call.
- SIM-call answer/end APIs are restricted by Android and vary by OS version and device vendor. ANSWER_PHONE_CALLS does not guarantee identical behaviour on all devices. The Gateway first uses the public Telecom API; if that throws or reports failure, it can try a matching action exposed by the system Phone app's call notification. This cannot help if no suitable action is exposed or Notification Access is disabled.
- Phone-state broadcasts may indicate an outgoing call started without exposing its number. Caller/dialled number availability varies by Android version, permissions, and device. On startup, CallCompanion also queries the current call state as a snapshot, but this cannot reliably recover caller number or call direction.
- Cellular call audio is managed by Android Telecom/Bluetooth. An ordinary app cannot capture and inject cellular-call audio, so keep the Gateway connected to the Bluetooth speakerphone.
- The call-notification fallback invokes only an action supplied by the OEM Phone app. It does not use hidden APIs, reflection, root, ADB grants, or private broadcasts. If the manufacturer exposes no action and blocks Telecom control, Android offers no universal app-level workaround.
- Bluetooth uses the secure paired RFCOMM socket. CallCompanion intentionally does not silently downgrade to an insecure socket or bypass pairing. If connection fails, remove and re-pair both phones in Android Bluetooth settings, then restart both relays.

## Build

Every push to main triggers GitHub Actions to build and publish an APK release. Local build: ./gradlew assembleDebug (JDK 17 and Android SDK required).
