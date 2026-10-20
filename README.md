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
6. **Optional, both phones:** in Android **Accessibility** settings, enable **PCL call & hotspot helper**. On the Remote it notices taps on WhatsApp call buttons; on the Gateway it lets PCL switch the Wi-Fi hotspot for those calls (see [WhatsApp calls](#whatsapp-calls)).
7. On **Home**, each phone selects the other one from its paired devices, then press **Start** on both. The Gateway only accepts connections from the Remote you selected.
8. Let PCL run in the background. On Xiaomi/Redmi/POCO, Huawei/Honor, OPPO/realme/OnePlus, vivo and some Samsung builds, set PCL's battery usage to **Unrestricted**, allow auto-start if offered, and lock it in Recents. Menu names vary.

While the link is on, PCL tries to restart by itself after the phone reboots, after an update, or if Android stops it. **Stop** or **Quit** turns that off until you press Start again.

PCL needs Android 8.0 or newer. It never needs the microphone.

## Features

| Feature | What it does |
|---|---|
| SIM calls | The Remote rings for incoming Gateway calls and shows the caller (and contact name, with Contacts permission on the Gateway). It can answer, reject, end, and dial through the Gateway's SIM. |
| Contacts | The Gateway's contacts are copied to the Remote for offline search. On each connection the phones compare a fingerprint and only resync when the list changed; **Sync contacts from Gateway** forces a refresh. |
| Notifications | The Gateway mirrors its notifications to the Remote. Dismissing a copy dismisses the original; actions offered by the original app (such as Answer on a call notification) can be used from the Remote. Can be turned off in the Gateway's settings. |
| WhatsApp | Detects WhatsApp call state on both phones, avoids duplicate call alerts, can open a WhatsApp chat on the Gateway from the Remote, and can switch the Gateway hotspot on for calls handled on the Remote. See below. |
| Link status | Home and the status notification show PCL's own Bluetooth traffic. This is app traffic only, not internet speed. |
| Activity log | Home keeps a short log for troubleshooting, with a Copy button. It never shows full phone numbers or contact names (only the last two digits). |

## WhatsApp calls

WhatsApp has no public call API, so PCL reads call state from the **action buttons on WhatsApp's notifications** (Answer/Decline means ringing; Hang up means active). This needs Notification access on both phones. It is best effort: a WhatsApp update or a manufacturer's layout can change those labels.

**Which phone shows the call.** If the Remote's own WhatsApp is ringing, PCL hides the Gateway's mirrored copy. If the Remote has no call of its own, the Gateway's copy is shown as a fallback, and its Answer button works from the Remote. Answer is blocked when either phone already has an active WhatsApp call. PCL never answers, rejects or mutes a call by itself. A short duplicate can appear before the Remote's state reaches the Gateway.

**Calling from the Remote.** Pick a contact or enter a number, then tap **WhatsApp chat on Gateway**. The Gateway shows a **Continue in WhatsApp** notification; tap it there and start the call in WhatsApp. PCL can't place WhatsApp calls itself.

**Hotspot for calls on the Remote.** The Remote's always-on internet is Bluetooth tethering (see [Internet on the Remote](#internet-on-the-remote)), which is slow for calls. When a WhatsApp call becomes active on the Remote, the Gateway can turn on its Wi-Fi hotspot, and turn it off afterwards:

- It waits a few seconds before turning on and about 15 seconds before turning off, so brief notification changes don't flip the network.
- Tapping a call button in WhatsApp on the Remote holds the hotspot request for one minute. If no call follows, the request lapses.
- It does nothing if the hotspot is already on or the Remote is already on Wi-Fi. A call that only rings, or is answered on the Gateway, never triggers it.
- It only turns off a hotspot it turned on, and only counts it as "turned on by PCL" once Android confirms the change.
- With the accessibility helper enabled, PCL opens hotspot settings and taps the Wi-Fi hotspot switch (never USB or Bluetooth tethering). It won't open Settings while the Gateway is in a phone call; it waits for the call to end.
- In every other case, or if automation fails, a notification on the Gateway offers **Open hotspot settings** so you can switch it yourself.
- Home on both phones shows what it's doing (waiting, switching on, on, not needed, off) and says plainly when you need to switch the hotspot yourself.
- If the Bluetooth link drops, the Remote reports its call state again when it reconnects, so a hotspot left on for a finished call is turned off then.
- On the Remote, if Wi-Fi is off or it hasn't joined the hotspot, a notification offers a Wi-Fi settings shortcut. Android doesn't let apps connect to Wi-Fi themselves.

**Accounts.** Using the same WhatsApp account on both phones requires WhatsApp's official linked-device mode; WhatsApp, not PCL, coordinates the call across those devices. PCL tracks state per WhatsApp app (WhatsApp or WhatsApp Business), not per account.

## Internet on the Remote

PCL's Bluetooth link carries call state and commands, not internet. The Remote gets the Gateway's mobile data through Android's own tethering:

1. **Bluetooth tethering — always on, low power.** On the Gateway, open **Settings → Hotspot & tethering** (wording varies) and turn on **Bluetooth tethering**. On the Remote, open the Gateway in Bluetooth settings and allow **Internet access** if offered. Turn Wi-Fi off on the Remote and check a web page loads. Some phones turn Bluetooth tethering off after a reboot, so check it again then. PCL never switches Bluetooth tethering on or off.
2. **Wi-Fi hotspot — for calls.** Keep the Gateway hotspot off normally. PCL turns it on for an active WhatsApp call on the Remote and off afterwards (see [WhatsApp calls](#whatsapp-calls)). Save the hotspot's Wi-Fi network on the Remote once so it joins automatically.

Android chooses which network the Remote uses, and moving from Bluetooth to Wi-Fi during a call can briefly interrupt it on some phones. The Remote's own apps, including its WhatsApp, use its own accounts. Mobile-data charges may apply.

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
