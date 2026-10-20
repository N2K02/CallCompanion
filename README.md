# PCL — Android Bluetooth Call Companion

[![Latest release](https://img.shields.io/github/v/release/N2K02/PCL?label=download)](https://github.com/N2K02/PCL/releases/latest) ![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)

**Keep your SIM in one Android phone and handle its calls from a second Android phone nearby.**

The phone with the SIM and WhatsApp is the **Gateway**. The phone you hold is the **Remote**. Over Bluetooth, the Remote rings when the Gateway gets a SIM or WhatsApp call, lets you answer, decline, end or place calls, shows the Gateway's notifications, and searches its contacts. Calls and their audio stay on the Gateway. The Remote needs no SIM, no WhatsApp and no internet.

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
│ PCL in Remote role      │  answer, decline, end, dial.
└─────────────────────────┘  No SIM, WhatsApp or internet.
```

The Bluetooth link carries call state, notifications, contacts and commands. It does **not** carry call audio or internet, or copy app accounts. You hear and speak through the Gateway's earpiece, loudspeaker or a Bluetooth headset connected to it. No PCL server is involved.

## Setup

1. Install PCL on both phones, and keep both on the same version. Each phone updates separately; Home shows a warning when the two versions differ.
2. Pair the two phones in Android **Settings → Bluetooth**.
3. **Android 13 and newer, Gateway:** open **Settings → Apps → PCL**, tap **⋮** and choose **Allow restricted settings**. Android blocks Notification access and Accessibility for apps installed from an APK until you do this.
4. **On the Gateway**, open PCL, choose **Gateway** in Settings, and grant:
   - Phone, Nearby devices and Notifications (required)
   - **Notification access** (required)
   - Contacts (optional: contact sync and caller names)
5. **On the Remote**, choose **Remote** and grant Nearby devices and Notifications. Contacts is optional (adds this phone's contacts to search). The Remote needs no Notification access.
6. **Optional, Gateway only:** in Android **Accessibility** settings, enable **PCL WhatsApp call helper**, so the Gateway can start WhatsApp calls you ask for from the Remote (see [WhatsApp calls](#whatsapp-calls)). The Remote doesn't need it.
7. On **Home**, each phone selects the other one from its paired devices, then press **Start** on both. The Gateway only accepts connections from the Remote you selected.
8. Let PCL run in the background. On Xiaomi/Redmi/POCO, Huawei/Honor, OPPO/realme/OnePlus, vivo and some Samsung builds, set PCL's battery usage to **Unrestricted**, allow auto-start if offered, and lock it in Recents. Menu names vary.

While the link is on, PCL tries to restart by itself after the phone reboots, after an update, or if Android stops it. **Stop** or **Quit** turns that off until you press Start again.

PCL needs Android 8.0 or newer. It never needs the microphone.

## Features

| Feature | What it does |
|---|---|
| SIM calls | The Remote rings for incoming Gateway calls and shows the caller (and contact name, with Contacts permission on the Gateway). It can answer, decline, end, and dial through the Gateway's SIM. A call answered on the Gateway itself stops the Remote ringing and isn't shown there; a call placed on the Gateway isn't shown either. A call nobody answered leaves a **Missed call** notification with **Call back**. |
| Calls tab | On the Remote, next to Home. See [Calls tab](#calls-tab). |
| Contacts | The Gateway's contacts are copied to the Remote for offline search. On each connection the phones compare a fingerprint and only resync when the list changed; the sync button in **Calls → Contacts** forces a refresh. |
| Notifications | The Gateway mirrors its notifications to the Remote. Dismissing a copy dismisses the original; actions offered by the original app can be used from the Remote. Calls always go to the Remote; other notifications can be turned off in the Gateway's settings (**Send other notifications to remote**). |
| WhatsApp | WhatsApp calls on the Gateway ring on the Remote, and the Remote can start one. See [WhatsApp calls](#whatsapp-calls). |
| Link traffic | Home and the status notification show what PCL sends between the phones over Bluetooth. |
| Activity log | Home keeps a short log for troubleshooting, with a Copy button. It never shows full phone numbers or contact names (only the last two digits). |

## Calls tab

The Remote has a **Calls** tab next to Home, laid out like a phone's dialer:

- **Keypad.** A dial pad with key tones (when the ringer is on) and haptics. Hold **0** for **+**, tap ⌫ to delete and hold it to clear, and long-press the number to paste one. Typing on the keys also searches contacts by name, like a phone's smart dial: 5‑6‑2 finds "Joan". Below the keys: **SIM call** (through the Gateway's SIM) and **WhatsApp** (in the Gateway's WhatsApp).
- **Recents.** SIM and WhatsApp calls on the Gateway, grouped by day: incoming, outgoing (with talk time), missed and declined. Missed calls show as a badge on the Calls icon until you open Recents. The list stays on the Remote only; **Clear** empties it.
- **Contacts.** Search the synced contacts, sync again, and star **favourites**. Favourites appear at the top of Contacts and above the keypad.
- Tap any person in Recents or Contacts for **WhatsApp**, **Favourite** and **Edit** (put the number on the keypad); the green phone icon calls straight away.
- While a SIM or WhatsApp call rings or is connected, the top of the tab shows it with **Answer / Decline** or **Hang up** and a call timer. Tapping a call notification opens this tab, and on a locked Remote a ringing call opens it straight away.

Android only lets the phone's own dialer send keypad tones into a call, so PCL can't press keys in a menu ("press 1") on the Gateway's call.

## WhatsApp calls

WhatsApp has no public call API, so PCL reads call state from **WhatsApp's call notification**: Android's call style when WhatsApp uses it (incoming or ongoing, with the Answer / Decline / Hang up buttons Android draws), otherwise the notification's own buttons (Answer and Decline means ringing; Hang up means active). This needs Notification access on the Gateway. It is best effort: a WhatsApp update or a language PCL doesn't know can change those labels. If a WhatsApp call notification's buttons aren't recognised, the activity log lists them.

**Incoming WhatsApp calls on the Gateway.** The Remote shows the call ("WhatsApp · on the Gateway") with **Decline** and **Answer**, and rings like for a SIM call (it stops after 75 seconds at most).

1. **Answer** on the Remote answers the call in the Gateway's WhatsApp. You talk through the Gateway: its earpiece, loudspeaker or Bluetooth headset.
2. The Remote's notification then shows the call in progress with a call timer and **Hang up**.
3. **Hang up** on the Remote ends the call in the Gateway's WhatsApp, and the notification goes away.
4. If you answer on the Gateway itself instead, the Remote stops ringing and lets the Gateway have the call. Calls you start on the Gateway aren't shown on the Remote either.
5. A call nobody answered leaves a **Missed WhatsApp call** notification on the Remote, with **Call back** (in the Gateway's WhatsApp) when the caller is a synced contact or shows a number.

This needs Notification access on the Gateway. On Android 14 and newer, also turn on **PCL WhatsApp call helper** on the Gateway: WhatsApp may open its call screen when answering, and Android only lets PCL do that from the background while the helper is on. PCL never answers, declines or mutes a call by itself.

**Calling from the Remote.** Pick a contact or enter a number in the Calls tab, then tap **WhatsApp**. The Gateway starts a WhatsApp voice call in its own WhatsApp, and the Remote says whether it started and then shows the call with **Hang up**. You talk through the Gateway (or its Bluetooth headset), as with SIM calls.

- For a saved contact who uses WhatsApp, the Gateway opens WhatsApp's own "Voice call" entry for that contact, which starts the call straight away.
- For any other number, WhatsApp opens the chat and PCL taps its voice-call button. It only taps in a chat whose top bar shows that contact's name or number. Typed numbers need their country code (+44…) unless the Gateway's SIM country matches.
- If WhatsApp asks "start this call?", PCL confirms it.
- This needs **PCL WhatsApp call helper** turned on in the Gateway's Accessibility settings: Android only lets an app open another app from the background through such a service. Without it, the Gateway shows a **Start WhatsApp call** notification that starts the call in one tap.
- The Gateway must be unlocked, or locked without a PIN (swipe, or kept unlocked by Smart Lock). A PIN, pattern or password can't be skipped.
- Both phones need this version of PCL. An older Gateway only shows a **Continue in WhatsApp** notification.

PCL follows WhatsApp and WhatsApp Business on the Gateway, one call per app at a time.

## Internet

The Remote needs no internet: WhatsApp calls run on the Gateway with the Gateway's mobile data. The Remote only needs internet (Wi-Fi, for example) to download PCL updates; otherwise install the new APK on it by hand.

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
