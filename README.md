# PCL — Android Bluetooth Call Companion

[![Latest release](https://img.shields.io/github/v/release/N2K02/PCL?label=download)](https://github.com/N2K02/PCL/releases/latest) ![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)

**Keep your SIM in one Android phone and handle its calls from a second Android phone nearby.**

The phone with the SIM is the **Gateway**. The phone you hold is the **Remote**. Over Bluetooth, the Remote rings when the Gateway gets a call, lets you answer, reject, end or dial, shows the Gateway's notifications, and searches its contacts. Call audio stays on the Gateway.

**[⬇ Download the latest APK](https://github.com/N2K02/PCL/releases/latest)** · [All releases](https://github.com/N2K02/PCL/releases)

## How it works

Install the same app on both phones and pick a role on each.

```text
┌─────────────────────────┐
│ GATEWAY PHONE           │  SIM, Phone app, WhatsApp,
│ PCL in Gateway role     │  mobile data. Call audio
└────────────┬────────────┘  stays on this phone.
             │
             │  paired Bluetooth (RFCOMM):
             │  call state, notifications,
             │  contacts and commands
             │
┌────────────┴────────────┐
│ REMOTE PHONE            │  Rings for Gateway calls;
│ PCL in Remote role      │  answer, reject, end, dial.
└─────────────────────────┘
```

The Bluetooth link carries call state, notifications, contacts and commands. It does **not** carry call audio, share internet, or copy app accounts. You hear and speak through the Gateway's earpiece, loudspeaker or a Bluetooth headset connected to it. No PCL server is involved.

## Setup

1. Install PCL on both phones, and keep both on the same version. Each phone updates separately; Home shows a warning when the two versions differ.
2. Pair the two phones in Android **Settings → Bluetooth**.
3. **Android 13 and newer:** on each phone, open **Settings → Apps → PCL**, tap **⋮** and choose **Allow restricted settings**. Android blocks Notification access and Accessibility for apps installed from an APK until you do this.
4. **On the Gateway**, open PCL, choose **Gateway** in Settings, and grant:
   - Phone, Nearby devices and Notifications (required)
   - **Notification access** (required)
   - Contacts (optional: contact sync and caller names)
5. **On the Remote**, choose **Remote** and grant Nearby devices, Notifications and **Notification access**. Contacts is optional (adds this phone's contacts to search).
6. **Optional, Gateway only:** in Android **Accessibility** settings, enable **PCL WhatsApp call helper**, so the Gateway can start WhatsApp calls you ask for from the Remote (see [WhatsApp calls](#whatsapp-calls)). The Remote doesn't need it.
7. On **Home**, each phone selects the other one from its paired devices, then press **Start** on both. The Gateway only accepts connections from the Remote you selected.
8. Let PCL run in the background. On Xiaomi/Redmi/POCO, Huawei/Honor, OPPO/realme/OnePlus, vivo and some Samsung builds, set PCL's battery usage to **Unrestricted**, allow auto-start if offered, and lock it in Recents. Menu names vary.

While the link is on, PCL tries to restart by itself after the phone reboots, after an update, or if Android stops it. **Stop** or **Quit** turns that off until you press Start again.

PCL needs Android 8.0 or newer. It never needs the microphone.

## Features

| Feature | What it does |
|---|---|
| SIM calls | The Remote rings for incoming Gateway calls and shows the caller (and contact name, with Contacts permission on the Gateway). It can answer, reject, end, and dial through the Gateway's SIM. A call nobody answered leaves a **Missed call** notification with **Call back**. |
| Calls tab | On the Remote, next to Home. See [Calls tab](#calls-tab). |
| Contacts | The Gateway's contacts are copied to the Remote for offline search. On each connection the phones compare a fingerprint and only resync when the list changed; the sync button in **Calls → Contacts** forces a refresh. |
| Notifications | The Gateway mirrors its notifications to the Remote. Dismissing a copy dismisses the original; actions offered by the original app (such as Answer on a call notification) can be used from the Remote. Can be turned off in the Gateway's settings. |
| WhatsApp | Detects WhatsApp call state on both phones, avoids duplicate call alerts, and can start a WhatsApp call on the Gateway from the Remote. See below. |
| Link status | Home and the status notification show PCL's own Bluetooth traffic. This is app traffic only, not internet speed. |
| Activity log | Home keeps a short log for troubleshooting, with a Copy button. It never shows full phone numbers or contact names (only the last two digits). |

## Calls tab

The Remote has a **Calls** tab next to Home, laid out like a phone's dialer:

- **Keypad.** A dial pad with key tones (when the ringer is on) and haptics. Hold **0** for **+**, tap ⌫ to delete and hold it to clear, and long-press the number to paste one. Typing on the keys also searches contacts by name, like a phone's smart dial: 5‑6‑2 finds "Joan". Below the keys: **SIM call** (through the Gateway's SIM) and **WhatsApp** (in the Gateway's WhatsApp).
- **Recents.** Calls made and received through the Gateway, grouped by day: incoming, outgoing (with talk time), missed, declined and WhatsApp. Missed calls show as a badge on the Calls icon until you open Recents. The list stays on the Remote only; **Clear** empties it.
- **Contacts.** Search the synced contacts, sync again, and star **favourites**. Favourites appear at the top of Contacts and above the keypad.
- Tap any person in Recents or Contacts for **WhatsApp**, **Favourite** and **Edit** (put the number on the keypad); the green phone icon calls straight away.
- While a SIM call rings or is connected, the top of the tab shows it with **Answer / Decline** or **Hang up** and a call timer.

Android only lets the phone's own dialer send keypad tones into a call, so PCL can't press keys in a menu ("press 1") on the Gateway's call.

## WhatsApp calls

WhatsApp has no public call API, so PCL reads call state from **WhatsApp's call notification**: Android's call style when WhatsApp uses it (incoming or ongoing, with the Answer / Decline / Hang up buttons Android draws), otherwise the notification's own buttons (Answer and Decline means ringing; Hang up means active). This needs Notification access on both phones. It is best effort: a WhatsApp update or a language PCL doesn't know can change those labels. If a WhatsApp call notification's buttons aren't recognised, the activity log lists them.

**Which phone shows the call.** If the Remote's own WhatsApp is ringing, PCL hides the Gateway's mirrored copy. If the Remote has no call of its own, the Gateway's copy is shown as a fallback, and its Answer button works from the Remote. Answer is blocked when either phone already has an active WhatsApp call. PCL never answers, rejects or mutes a call by itself. A short duplicate can appear before the Remote's state reaches the Gateway.

**Calling from the Remote.** Pick a contact or enter a number, then tap **WhatsApp call on Gateway**. The Gateway starts a WhatsApp voice call in its own WhatsApp, and Home on the Remote says whether it started. You talk through the Gateway (or its Bluetooth headset), as with SIM calls.

- For a saved contact who uses WhatsApp, the Gateway opens WhatsApp's own "Voice call" entry for that contact, which starts the call straight away.
- For any other number, WhatsApp opens the chat and PCL taps its voice-call button. It only taps in a chat whose top bar shows that contact's name or number. Typed numbers need their country code (+44…) unless the Gateway's SIM country matches.
- If WhatsApp asks "start this call?", PCL confirms it.
- This needs **PCL WhatsApp call helper** turned on in the Gateway's Accessibility settings: Android only lets an app open another app from the background through such a service. Without it, the Gateway shows a **Start WhatsApp call** notification that starts the call in one tap.
- The Gateway must be unlocked, or locked without a PIN (swipe, or kept unlocked by Smart Lock). A PIN, pattern or password can't be skipped.
- Both phones need this version of PCL. An older Gateway only shows a **Continue in WhatsApp** notification.

**Accounts.** Using the same WhatsApp account on both phones requires WhatsApp's official linked-device mode; WhatsApp, not PCL, coordinates the call across those devices. PCL tracks state per WhatsApp app (WhatsApp or WhatsApp Business), not per account.

## Internet on the Remote

PCL's Bluetooth link carries call state and commands, not internet. The Remote gets the Gateway's mobile data through **Bluetooth tethering**, which Android provides:

1. On the Gateway, open **Settings → Hotspot & tethering** (wording varies) and turn on **Bluetooth tethering**.
2. On the Remote, open the Gateway in Bluetooth settings and allow **Internet access** if offered.
3. Turn Wi-Fi off on the Remote and check a web page loads.

Some phones turn Bluetooth tethering off after a reboot, so check it again then. PCL never switches tethering on or off. The Remote's own apps, including its WhatsApp, use its own accounts. Mobile-data charges may apply.

## Limitations

- **SIM call controls depend on Android and the manufacturer.** PCL uses Android's Telecom API. If that fails or isn't allowed, it falls back to a matching button on the system Phone app's call notification (keep that app's notifications enabled). It uses no hidden APIs, reflection, root or ADB. If neither route is available on a phone, PCL can't answer or end calls there.
- **Caller numbers aren't always available.** Android may report an outgoing call without its number. On startup PCL reads the current call state, but not the number or direction.
- **Bluetooth uses the secure, paired RFCOMM socket** and never falls back to an insecure one. If the phones can't connect, remove the pairing on both, pair again, and restart PCL on both.
- **Battery:** PCL connects only to the selected paired phone and never scans. Reconnect attempts back off from 3 to 30 seconds while the other phone is unreachable. Turning off notification mirroring saves a little more.

## Build

Pushes to `main` that change the app build an APK and publish it as a GitHub release; documentation-only pushes don't. To build locally: `./gradlew assembleDebug` (JDK 17 and the Android SDK required). Local builds are versioned 0.1, below every release, so a release can always be installed over them.

## Links

- **Latest APK:** [GitHub Releases — latest](https://github.com/N2K02/PCL/releases/latest)
- **All releases:** [GitHub Releases](https://github.com/N2K02/PCL/releases)
- **Source and issues:** [github.com/N2K02/PCL](https://github.com/N2K02/PCL)
