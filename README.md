# CallCompanion

Use a second Android phone as a Bluetooth remote for a Gateway phone that has the SIM and remains connected to a Bluetooth hands-free speakerphone. Open Headunit is not required.

Install the same app on both phones and select a role:

- **Gateway** — the main phone. It has the SIM (for cellular calls), runs CallCompanion's Gateway role, monitors call state, and forwards supported notifications and commands. Keep it connected to the Bluetooth hands-free speakerphone if that is how you want to hear and speak during calls.
- **Remote** — the companion phone. It runs CallCompanion's Remote role, shows information received from the Gateway, and sends user actions back over Bluetooth. It is a control screen, not automatically a second handset for the Gateway's cellular-call audio.

### At a glance: how the two phones work together

```text
                 CALL STATUS, NOTIFICATIONS
                 AND CONTROL COMMANDS
       ┌─────────────────────────────────────┐
       │                                     ⇅
┌────────────────────┐  paired Bluetooth  ┌────────────────────┐
│ GATEWAY PHONE      │◄──── RFCOMM link ──►│ REMOTE PHONE       │
│                    │                     │                    │
│ • SIM / Phone app  │                     │ • CallCompanion UI │
│ • WhatsApp apps    │                     │ • Mirrored alerts  │
│ • CallCompanion    │                     │ • Answer/reject or │
│   Gateway role     │                     │   other available  │
│ • Mobile data/Wi-Fi│                     │   notification acts│
└─────────┬──────────┘                     └────────────────────┘
          │
          ⇄ Bluetooth hands-free speakerphone
            (when configured for call audio)
```

**What the diagram means:** the Bluetooth link carries call state, notification information, and control requests. It does not carry the Gateway's cellular-call audio, clone app sessions, or automatically share internet. The Gateway needs internet for its own internet-calling apps. Remote internet access requires a separate working connection (for example, a supported Android tethering/hotspot setup).


## Features

| Feature | Behaviour |
|---|---|
| Incoming SIM call state | Gateway forwards ringing state and caller number when Android provides it; contact names can be resolved if Contacts permission is granted. |
| Outgoing SIM call state | Gateway reports an outgoing-call transition when it can infer it from telephony state. Android may not expose the dialled number to a normal app. |
| Contact sync | Contacts can be synced from Gateway to Remote at connection time or manually with **Sync contacts from Gateway**. The Remote keeps a local offline copy. Gateway Contacts permission is required; Remote Contacts permission is optional for merging its own contacts into search. |
| WhatsApp outgoing handoff | Remote can ask Gateway to post a notification that opens a WhatsApp chat for the entered number. Tap that notification on Gateway, then place the voice call inside WhatsApp. This is a chat handoff, not an automated call-placement API. |
| Answer/reject/end SIM calls | Remote sends a command to the Gateway. Actual success depends on Android/OEM telephony permissions and API behaviour; a normal app cannot guarantee all call controls on every device. |
| Third-party call notifications | With Notification Access enabled on the Gateway, supported call notifications from apps such as WhatsApp can be mirrored to the Remote. |
| Third-party call actions | If the originating app exposes notification action buttons (for example, Answer or Decline), CallCompanion mirrors up to three available actions and asks the Gateway to invoke the original action. Not every app exposes usable actions. |
| Other notifications | Non-ongoing notifications can be mirrored to the Remote. Persistent non-call notifications are ignored. |
| Bluetooth transport | Call state, notification details, and commands travel over the existing Bluetooth RFCOMM connection. |
| Call audio | The Gateway remains paired to the Bluetooth speakerphone, so the conversation audio stays between the Gateway and that device. CallCompanion does not relay cellular-call audio to the Remote. |

## How the app works — step by step

### 1. Initial setup

1. Install CallCompanion on both Android phones.
2. Pair the Gateway and Remote in Android **Settings → Bluetooth**.
3. On the Gateway, choose **Gateway**, grant the required phone/Bluetooth permissions, and enable **Notification access**. Grant Contacts permission if you want contact sync and caller-name lookup.
4. On the Remote, choose **Remote**, select the paired Gateway, and start the link.
5. Keep CallCompanion running on both phones. The app uses a paired Bluetooth RFCOMM connection; it does not need a CallCompanion cloud server.

### 2. Incoming or outgoing SIM call

1. The SIM call happens on the Gateway.
2. Android reports call-state information that is available to CallCompanion.
3. The Gateway sends the state and any available caller details to the Remote over Bluetooth.
4. The Remote can send **Answer**, **Reject**, or **End** requests back to the Gateway. Whether Android accepts each request depends on the phone, OS version, permissions, and exposed call controls.
5. The Gateway's Android audio route handles the conversation. If paired and selected, the Bluetooth hands-free speakerphone carries the call audio; the Remote does not receive that audio through CallCompanion.

### 3. Third-party call notifications (for example, WhatsApp)

1. The app posts a notification on the Gateway.
2. If Notification Access is enabled, CallCompanion forwards the supported notification details and any action buttons the original app exposes.
3. The Remote displays the notification and can send an available action back to the Gateway.
4. The Gateway attempts to invoke that original notification action. Some apps do not expose Answer/Decline actions, and CallCompanion cannot create actions that the app does not provide.

### 4. Starting a WhatsApp chat/call from the Remote

1. Choose a synced contact or enter a phone number on the Remote.
2. Use **WhatsApp chat on Gateway**.
3. The Gateway receives a **Continue in WhatsApp** notification. Tap it on the Gateway to open the chat.
4. Start the call using WhatsApp on the Gateway. This is a chat handoff, not a remote direct-call API or automated call placement.

### 5. Contacts and notifications

- The Gateway can send its contacts to the Remote, which keeps an offline copy for searching.
- Notification mirroring can be turned on or off in the Gateway's settings.
- Only notification actions exposed by the original app can be relayed.

### 6. Internet sharing is separate

CallCompanion's Bluetooth control link does **not** itself share internet. If you want the Remote to use the Gateway's mobile data for all apps, enable a supported Android tethering feature separately. See [Sharing the Gateway's internet with the Remote](#sharing-the-gateways-internet-with-the-remote). Bluetooth tethering client support varies by phone model.


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

### Starting an outgoing WhatsApp call

1. On the Remote, choose a synced contact or enter a phone number in international format.
2. Tap **WhatsApp chat on Gateway**.
3. The Gateway displays a **Continue in WhatsApp** notification. Tap it on the Gateway to open the chat; Android may use WhatsApp or a browser depending on installed apps and link settings.
4. Start the voice call using WhatsApp's own call button. The Gateway's Android audio routing determines whether the call uses its paired hands-free speakerphone.

WhatsApp does not expose a stable public Android API for third-party apps to place arbitrary outgoing voice calls directly. CallCompanion therefore does not pretend the chat handoff is a direct call, and does not use brittle private WhatsApp intents or simulated taps.

The phones communicate locally over Bluetooth; no CallCompanion cloud server is involved. The Gateway itself still needs whatever internet connection the third-party calling app requires. This app mirrors call status and controls only—it does not forward call audio.

## Sharing the Gateway's internet with the Remote

CallCompanion's Bluetooth RFCOMM link is for call status and control; it does **not** automatically provide internet access to the Remote. To try sharing the Gateway's mobile data for all apps on the Remote:

1. On the Gateway, open **Settings → Hotspot & tethering** (the menu name varies) and enable **Bluetooth tethering**. Keep mobile data and Bluetooth enabled.
2. On the Remote, pair with the Gateway in Android Bluetooth settings. Some Android phone models support connecting to another phone's Bluetooth PAN internet connection; others expose no client option. If the Remote does not obtain internet, this is an Android/OEM limitation rather than a CallCompanion relay failure.
3. In CallCompanion → **Settings → Internet sharing**, use the shortcut to open the relevant Android settings and follow the on-screen guidance.

Android does not allow an ordinary app to silently enable system Bluetooth tethering on all devices, so CallCompanion guides you to the system setting instead of claiming to toggle it automatically. Bluetooth tethering can be slower than Wi-Fi hotspot and mobile-data charges may apply.

Once the Remote has a working internet connection, its own WhatsApp or WhatsApp Business installation can use its signed-in account to make calls. Internet sharing does not clone the Gateway's WhatsApp account, transfer account sessions, or relay call audio. To use the same WhatsApp account on both phones, use WhatsApp's official linked-device/companion mode; for a separate WhatsApp Business account, sign in to that account on the Remote.

## Android compatibility and setup

The app's minimum supported Android version is Android 8.0 (API 26), so the monitoring and Bluetooth flow covers Android 10 and newer. Android 12+ requires the Nearby devices/Bluetooth Connect runtime permission. Android 13+ also requires notification permission for CallCompanion's own notifications. Microphone permission is optional and is requested only for the separate app voice-link feature; it is not needed for SIM call status or remote call controls. Contacts permission is requested separately for contact sync/search and is not needed to keep the Bluetooth relay running.

1. Install the same APK on both phones.
2. Pair the two phones in Android **Settings → Bluetooth**.
3. Pair the Gateway phone with your Bluetooth speakerphone.
4. On the Gateway, choose **Gateway**, grant Phone and Nearby devices permissions, grant **Contacts** if you want contact sync, and enable **Notification access** in Android settings.
5. On the Remote, choose **Remote**, select the paired Gateway, and start the connection.
6. Keep CallCompanion running on both phones. In the Remote's **Make a call** section, use **Sync contacts from Gateway** whenever you want to refresh the offline contact cache. On Xiaomi/Redmi/POCO, Huawei/Honor, OPPO/realme/OnePlus, vivo, and some Samsung builds, set CallCompanion battery usage to **Unrestricted** / **Allow background activity**, allow auto-start if offered, and lock the app in Recents if available. Menu names vary. Enable notifications for the system Phone app if you want the call-notification fallback.

## Battery and background reliability

- When Bluetooth connection attempts fail, the reconnect loop increases the retry interval from 3 seconds up to 30 seconds. A session that stays connected for at least 15 seconds resets the retry delay, so normal reconnects stay quick without repeatedly waking the radio during a prolonged outage.
- The app does not continuously scan for nearby Bluetooth devices; it connects to the selected, already-paired device. Keep the relay foreground service running for call notifications and controls.
- The two audio capture/playback threads run only while the optional **Voice link** is active. The audio capture loop backs off briefly if an OEM audio driver returns zero bytes, rather than spinning continuously. The Voice link automatically stops when a SIM call starts and cannot be started during an active SIM call, to avoid competing with Android's call-audio routing.
- For best standby life, leave Voice link off when not needed, disable notification mirroring if you do not use it, and allow the OS/OEM background setting described above. Battery optimisation exceptions improve reliability but may increase standby power use.

## Important Android limitations

- NotificationListenerService requires user-granted Notification Access. It can invoke only actions supplied by the originating notification; it cannot universally answer or end every third-party app call.
- SIM-call answer/end APIs are restricted by Android and vary by OS version and device vendor. ANSWER_PHONE_CALLS does not guarantee identical behaviour on all devices. The Gateway first uses the public Telecom API when available; if it throws, reports failure, the required permission is unavailable, or the Android version lacks the call-control API, it can try a matching action exposed by the system Phone app's call notification. This cannot help if no suitable action is exposed or Notification Access is disabled.
- Phone-state broadcasts may indicate an outgoing call started without exposing its number. Caller/dialled number availability varies by Android version, permissions, and device. On startup, CallCompanion also queries the current call state as a snapshot, but this cannot reliably recover caller number or call direction.
- Cellular call audio is managed by Android Telecom/Bluetooth. An ordinary app cannot capture and inject cellular-call audio, so keep the Gateway connected to the Bluetooth speakerphone.
- The call-notification fallback invokes only an action supplied by the OEM Phone app. It does not use hidden APIs, reflection, root, ADB grants, or private broadcasts. If the manufacturer exposes no action and blocks Telecom control, Android offers no universal app-level workaround.
- Bluetooth uses the secure paired RFCOMM socket. CallCompanion intentionally does not silently downgrade to an insecure socket or bypass pairing. If connection fails, remove and re-pair both phones in Android Bluetooth settings, then restart both relays.

## Build

Every push to main triggers GitHub Actions to build and publish an APK release. Local build: ./gradlew assembleDebug (JDK 17 and Android SDK required).
