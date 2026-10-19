# PCL — Android Bluetooth Call Companion

[![Latest release](https://img.shields.io/github/v/release/N2K02/PCL?label=download)](https://github.com/N2K02/PCL/releases/latest) ![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)

**Android Bluetooth call companion app:** use a second Android phone as a remote control for a SIM-equipped gateway phone. The app relays supported call status, notifications, and commands over a paired Bluetooth connection; cellular call audio is not relayed by the app.

**[⬇ Download the latest APK](https://github.com/N2K02/PCL/releases/latest)** · [All releases](https://github.com/N2K02/PCL/releases)

Use a second Android phone as a Bluetooth remote for a Gateway phone that has the SIM. Android on the Gateway handles call audio through its earpiece, loudspeaker, or a connected Bluetooth hands-free device.

Install the same app on both phones and select a role:

- **Gateway** — the main phone. It has the SIM (for cellular calls), runs PCL's Gateway role, monitors call state, and forwards supported notifications and commands. Android handles conversation audio through the Gateway's earpiece, loudspeaker, or an optional connected Bluetooth hands-free device.
- **Remote** — the companion phone. It runs PCL's Remote role, shows information received from the Gateway, and sends user actions back over Bluetooth. It is a control screen, not automatically a second handset for the Gateway's cellular-call audio.

### At a glance: how the two phones work together

```text
                 CALL STATUS, NOTIFICATIONS
                 AND CONTROL COMMANDS
       ┌─────────────────────────────────────┐
       │                                     ⇅
┌────────────────────┐  paired Bluetooth   ┌────────────────────┐
│ GATEWAY PHONE      │◄──── RFCOMM link ──►│ REMOTE PHONE       │
│                    │                     │                    │
│ • SIM / Phone app  │                     │ • PCL UI │
│ • WhatsApp apps    │                     │ • Mirrored alerts  │
│ • PCL    │                               │ • Answer/reject or │
│   Gateway role     │                     │   other available  │
│ • Mobile data/Wi-Fi│                     │   notification acts│
└─────────┬──────────┘                     └────────────────────┘
          │
          ⇄ Bluetooth hands-free speakerphone
            (when configured for call audio)
```

**What the diagram means:** the Bluetooth link carries call state, notification information, and control requests. It does not carry the Gateway's cellular-call audio, clone app sessions, or automatically share internet. The Gateway needs internet for its own internet-calling apps. Remote internet access can use the Gateway's on-demand Wi-Fi hotspot for linked WhatsApp calls; PCL's Bluetooth link itself does not carry internet.


## Features

| Feature | Behaviour |
|---|---|
| Incoming SIM call state | Gateway forwards ringing state and caller number when Android provides it; contact names can be resolved if Contacts permission is granted. |
| Outgoing SIM call state | Gateway reports an outgoing-call transition when it can infer it from telephony state. Android may not expose the dialled number to a normal app. |
| Contact sync | Contacts can be synced from Gateway to Remote at connection time or manually with **Sync contacts from Gateway**. The Remote keeps a local offline copy. Gateway Contacts permission is required; Remote Contacts permission is optional for merging its own contacts into search. |
| WhatsApp outgoing handoff | Remote can ask Gateway to post a notification that opens a WhatsApp chat for the entered number. Tap that notification on Gateway, then place the voice call inside WhatsApp. This is a chat handoff, not an automated call-placement API. |
| Answer/reject/end SIM calls | Remote sends a command to the Gateway. Actual success depends on Android/OEM telephony permissions and API behaviour; a normal app cannot guarantee all call controls on every device. |
| Third-party call notifications | Supported third-party call notifications can be mirrored to Remote. WhatsApp call notifications are suppressed when Remote has already detected its own native WhatsApp call as ringing or active; Gateway mirroring is a fallback when Remote has no detected local call. |
| Third-party call actions | PCL can relay actions exposed by the original notification. It detects WhatsApp call state on both phones when Notification Access exposes recognizable Answer/Decline or End-call actions, and blocks stale WhatsApp Answer actions when either phone is already in an active WhatsApp call. It never auto-answers or rejects a call. |
| Other notifications | Non-ongoing notifications can be mirrored to the Remote. Persistent non-call notifications are ignored. |
| Bluetooth transport | Call state, notification details, and commands travel over the existing Bluetooth RFCOMM connection. |
| PCL Bluetooth traffic speed | Home and the ongoing notification show approximate download/upload rates for PCL protocol frames sent and received over its Bluetooth RFCOMM link. This is app-level traffic only; it is not Bluetooth radio capacity, internet speed, or tethered internet throughput. Rates are unavailable while disconnected. |
| Call audio | Android manages the Gateway's cellular-call audio route (earpiece, loudspeaker, or a connected Bluetooth hands-free device). PCL relays call control and status only; it does not stream cellular-call audio to the Remote. |

## How the app works — step by step

### 1. Initial setup

1. Install PCL on both Android phones.
2. Pair the Gateway and Remote in Android **Settings → Bluetooth**.
3. On the Gateway, choose **Gateway**, grant the required phone/Bluetooth permissions, and enable **Notification access**. Grant Contacts permission if you want contact sync and caller-name lookup.
4. On the Remote, choose **Remote**, enable **Notification access** as well, select the paired Gateway, and start the link. Remote notification access is used to observe its local WhatsApp call state.
5. Keep PCL running on both phones. The app uses a paired Bluetooth RFCOMM connection; it does not need a PCL cloud server.

### 2. Incoming or outgoing SIM call

1. The SIM call happens on the Gateway.
2. Android reports call-state information that is available to PCL.
3. The Gateway sends the state and any available caller details to the Remote over Bluetooth.
4. The Remote can send **Answer**, **Reject**, or **End** requests back to the Gateway. Whether Android accepts each request depends on the phone, OS version, permissions, and exposed call controls.
5. Android handles the conversation audio on the Gateway. Use its native earpiece, loudspeaker, or connected Bluetooth hands-free device. The Remote controls the call but does not receive or transmit call audio through PCL.

### 3. Third-party call notifications

1. Enable **Notification access on both phones**. Remote observes its own WhatsApp call notification, while Gateway can mirror a WhatsApp call notification only when Remote has not reported a native WhatsApp call as ringing or active.
2. **Native WhatsApp takes priority.** If Remote is already ringing in WhatsApp, PCL suppresses the Gateway copy. If a mirrored copy was shown first and Remote subsequently reports its own call, PCL removes the mirrored copy. A Gateway copy remains a fallback when Remote has no detectable native call notification.
3. If a fallback mirrored notification is shown, you can tap an exposed **Answer** action on Remote; PCL forwards the original notification action to Gateway. Otherwise answer directly in WhatsApp on the phone where the native call is ringing.
4. PCL observes notification-based call state and blocks stale mirrored **Answer** actions when either phone is already observed to have an active WhatsApp call. It does not automatically answer, reject, mute, or end calls. Detection is best-effort: there can be a brief duplicate before Remote's state reaches Gateway, and some WhatsApp/OEM versions expose no recognizable notification actions.
5. When both installations use the same WhatsApp account through official linked-device mode, WhatsApp itself coordinates the actual call across devices. Separate WhatsApp accounts/profiles are separate call sessions and are not merged by PCL. Android notifications do not reliably identify the signed-in account, so duplicate suppression is conservative per WhatsApp app package and may also suppress a separate account's mirrored call in the same package.

### 4. Starting a WhatsApp chat/call from the Remote

1. Choose a synced contact or enter a phone number on the Remote.
2. Use **WhatsApp chat on Gateway**.
3. The Gateway receives a **Continue in WhatsApp** notification. Tap it on the Gateway to open the chat.
4. Start the call using WhatsApp on the Gateway. This is a chat handoff, not a remote direct-call API or automated call placement.

### 5. Contacts and notifications

- The Gateway can send its contacts to the Remote, which keeps an offline copy for searching.
- Notification mirroring can be turned on or off in the Gateway's settings.
- Only notification actions exposed by the original app can be relayed.

### 6. On-demand Gateway Wi-Fi hotspot for linked WhatsApp calls

PCL uses Bluetooth RFCOMM for call-state and control messages only; Bluetooth does not carry internet traffic or call audio. The Gateway must have internet access and shares it through its built-in Wi-Fi hotspot only when needed.

1. Enable Notification access on both phones. On Gateway, enable PCL's optional **Hotspot automation** accessibility service.
2. Save the Gateway hotspot network on Remote and enable automatic connection before testing.
3. When Gateway detects its incoming WhatsApp call notification, PCL requests hotspot activation and sends a Bluetooth call-state trigger to Remote. The Remote helper then asks Android to reconnect to a saved Wi-Fi network if Wi-Fi is enabled and the phone is disconnected.
4. Android controls network selection and auto-join. If Remote remains connected to another Wi-Fi network, Wi-Fi is disabled, or auto-join does not choose Gateway, open Wi-Fi settings and connect to Gateway manually. Android does not allow ordinary apps to force a specific saved Wi-Fi network silently on all supported versions.
5. Keep the hotspot active while Remote reports **RINGING** or **ACTIVE**. When Remote reports **IDLE**, PCL requests hotspot shutdown if no other Remote WhatsApp call remains active or ringing. If the Gateway call ends before Remote ever reports a call, PCL waits five seconds before cleanup.
6. PCL turns off the hotspot only if it enabled it. If the hotspot was already on before the call, PCL leaves it on.

Hotspot switching uses best-effort Accessibility interaction with Android Settings, not a universal public API. OEM layouts and confirmation dialogs may require manual action. WhatsApp's linked-device/companion mode must be set up on Remote, and a slow Wi-Fi reconnection can still mean the call arrives too late.

## Android compatibility and setup

The app's minimum supported Android version is Android 8.0 (API 26). Android 12+ requires the Nearby devices/Bluetooth Connect runtime permission. Android 13+ also requires notification permission for PCL's own notifications. PCL does not need microphone permission for SIM call status or remote call controls. Contacts permission is requested separately for contact sync/search and is not needed to keep the Bluetooth relay running.

1. Install the same APK on both phones.
2. Pair the two phones in Android **Settings → Bluetooth**.
3. Optional: pair a Bluetooth hands-free device with the Gateway if you prefer it over the Gateway's earpiece or loudspeaker.
4. On the Gateway, choose **Gateway**, grant Phone and Nearby devices permissions, grant **Contacts** if you want contact sync, and enable **Notification access** in Android settings.
5. On the Remote, choose **Remote**, select the paired Gateway, and start the connection.
6. Keep PCL running on both phones. In the Remote's **Make a call** section, use **Sync contacts from Gateway** whenever you want to refresh the offline contact cache. On Xiaomi/Redmi/POCO, Huawei/Honor, OPPO/realme/OnePlus, vivo, and some Samsung builds, set PCL battery usage to **Unrestricted** / **Allow background activity**, allow auto-start if offered, and lock the app in Recents if available. Menu names vary. Enable notifications for the system Phone app if you want the call-notification fallback.

## Battery and background reliability

- When Bluetooth connection attempts fail, the reconnect loop increases the retry interval from 3 seconds up to 30 seconds. A session that stays connected for at least 15 seconds resets the retry delay, so normal reconnects stay quick without repeatedly waking the radio during a prolonged outage.
- The app does not continuously scan for nearby Bluetooth devices; it connects to the selected, already-paired device. Keep the relay foreground service running for call notifications and controls.
- PCL does not capture or stream call audio. Android manages the Gateway's call-audio route; use the Gateway's native in-call controls to choose earpiece, loudspeaker, or a connected Bluetooth hands-free device.
- For best standby life, disable notification mirroring if you do not use it and allow the OS/OEM background setting described above. Battery optimisation exceptions improve reliability but may increase standby power use.

## Important Android limitations

- NotificationListenerService requires user-granted Notification Access on both phones for WhatsApp call-state observation and duplicate-notification suppression. Suppression is best-effort and depends on recognizable notification actions and Bluetooth state reporting; a short-lived duplicate may appear before Remote state arrives. Native WhatsApp notifications take priority once Remote reports RINGING or ACTIVE; Gateway mirroring is retained as a fallback when no local Remote call is detected. The state is tracked per WhatsApp app package, not per account. For hotspot automation, PCL waits for a Remote call to become active (or an outgoing call-button tap); a ringing notification alone does not trigger it. On call end, PCL attempts to disable only a hotspot it enabled. These actions depend on OEM accessibility UI and are not guaranteed. PCL does not change ringtone volume or automatically answer/reject calls. WhatsApp linked-device mode, not PCL, coordinates the underlying WhatsApp call across linked devices.
- SIM-call answer/end APIs are restricted by Android and vary by OS version and device vendor. ANSWER_PHONE_CALLS does not guarantee identical behaviour on all devices. The Gateway first uses the public Telecom API when available; if it throws, reports failure, the required permission is unavailable, or the Android version lacks the call-control API, it can try a matching action exposed by the system Phone app's call notification. This cannot help if no suitable action is exposed or Notification Access is disabled.
- Phone-state broadcasts may indicate an outgoing call started without exposing its number. Caller/dialled number availability varies by Android version, permissions, and device. On startup, PCL also queries the current call state as a snapshot, but this cannot reliably recover caller number or call direction.
- Cellular call audio is managed by Android Telecom. PCL only sends call-control commands and receives call-state updates; it does not relay audio to the Remote. The user must hear and speak through the Gateway's earpiece, loudspeaker, or a connected Bluetooth hands-free device.
- The call-notification fallback invokes only an action supplied by the OEM Phone app. It does not use hidden APIs, reflection, root, ADB grants, or private broadcasts. If the manufacturer exposes no action and blocks Telecom control, Android offers no universal app-level workaround.
- Bluetooth uses the secure paired RFCOMM socket. PCL intentionally does not silently downgrade to an insecure socket or bypass pairing. If connection fails, remove and re-pair both phones in Android Bluetooth settings, then restart both relays.

## Build

Every push to main triggers GitHub Actions to build and publish an APK release. Local build: ./gradlew assembleDebug (JDK 17 and Android SDK required).


## Official download and project links

Bluetooth call companion for Android: use a second Android phone as a remote control for a SIM-equipped gateway phone.

- **Latest Android APK:** [Download the latest release](https://github.com/N2K02/PCL/releases/latest)
- **All releases and version history:** [GitHub Releases](https://github.com/N2K02/PCL/releases)
- **Source code and issue reporting:** [PCL repository](https://github.com/N2K02/PCL)

The APK is distributed through GitHub Releases. If no release is available, check the repository's Releases page for future published builds. Google indexing is not guaranteed; this section provides clear crawlable links for visitors and search engines.
