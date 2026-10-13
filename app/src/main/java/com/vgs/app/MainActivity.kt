package com.vgs.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var roleGroup: RadioGroup
    private lateinit var rbGateway: RadioButton
    private lateinit var rbRemote: RadioButton
    private lateinit var deviceSection: LinearLayout
    private lateinit var deviceSpinner: Spinner
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var callCard: LinearLayout
    private lateinit var callText: TextView
    private lateinit var answerBtn: Button
    private lateinit var rejectBtn: Button
    private lateinit var hangupBtn: Button
    private lateinit var dialSection: LinearLayout
    private lateinit var dialInput: AutoCompleteTextView
    private lateinit var contactAdapter: ContactAdapter
    private var selectedContact: Contact? = null
    private var loadedContactsVersion = -1
    private lateinit var notifSection: LinearLayout
    private lateinit var notifStatus: TextView
    private lateinit var mirrorSwitch: Switch
    private lateinit var voiceBtn: Button
    private lateinit var speakerBtn: Button
    private lateinit var gwSpeakerBtn: Button
    private lateinit var logView: TextView

    private var devices: List<Pair<String, String>> = emptyList()
    private var gatewaySpeaker = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vgs", MODE_PRIVATE)
        setContentView(buildUi())
        requestPermissions(allPerms().toTypedArray(), 1)
        loadDevices()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Hub.listener = { refresh() }
        if (prefs.getString("role", null) == LinkService.ROLE_GATEWAY || rbGateway.isChecked) {
            NotifListener.rebind(this)
        }
        refresh()
    }

    override fun onPause() {
        Hub.listener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
        refresh()
    }

    // ---------------------------------------------------------------- permissions

    private fun corePerms(): List<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun gatewayPerms(): List<String> = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
    )

    private fun allPerms(): List<String> =
        corePerms() + gatewayPerms() + listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- actions

    private fun currentRole(): String =
        if (rbGateway.isChecked) LinkService.ROLE_GATEWAY else LinkService.ROLE_REMOTE

    private fun startLink() {
        val role = currentRole()
        val needed = if (role == LinkService.ROLE_GATEWAY) corePerms() + gatewayPerms() else corePerms()
        val missing = needed.filter { !granted(it) }
        if (missing.isNotEmpty()) {
            requestPermissions(
                (missing + listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS))
                    .filter { !granted(it) }.toTypedArray(), 2
            )
            toast("Please allow the permissions, then press Start again")
            return
        }
        val i = Intent(this, LinkService::class.java)
            .setAction(LinkService.ACTION_START)
            .putExtra(LinkService.EXTRA_ROLE, role)
        if (role == LinkService.ROLE_REMOTE) {
            val pos = deviceSpinner.selectedItemPosition
            if (devices.isEmpty() || pos < 0 || pos >= devices.size) {
                toast("Pair with the gateway phone in Bluetooth settings first")
                return
            }
            val addr = devices[pos].second
            prefs.edit().putString("device", addr).apply()
            i.putExtra(LinkService.EXTRA_DEVICE, addr)
        }
        prefs.edit().putString("role", role).apply()
        startForegroundService(i)
    }

    private fun stopLink() {
        startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP))
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        if (!::deviceSpinner.isInitialized) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = if (adapter == null ||
            (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
        ) {
            emptyList()
        } else {
            try {
                (adapter.bondedDevices ?: emptySet()).map { (it.name ?: it.address) to it.address }
            } catch (e: SecurityException) {
                emptyList()
            }
        }
        val names = if (devices.isEmpty()) listOf("No paired phones found")
        else devices.map { "${it.first}  (${it.second})" }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val saved = prefs.getString("device", null)
        val idx = devices.indexOfFirst { it.second == saved }
        if (idx >= 0) deviceSpinner.setSelection(idx)
    }

    // ---------------------------------------------------------------- UI state

    private fun refresh() {
        if (!::logView.isInitialized) return
        val s = Hub.service
        val running = s != null && s.isActive
        val role = if (running) s!!.role else currentRole()
        val isRemote = role == LinkService.ROLE_REMOTE

        rbGateway.isEnabled = !running
        rbRemote.isEnabled = !running
        deviceSection.visibility = if (isRemote && !running) View.VISIBLE else View.GONE
        startBtn.isEnabled = !running
        stopBtn.isEnabled = running

        statusText.text = if (!running) "● Stopped" else (if (s!!.connected) "● " else "○ ") + s.connectionState
        statusText.setTextColor(
            when {
                !running -> Color.GRAY
                s!!.connected -> Color.rgb(0, 140, 60)
                else -> Color.rgb(200, 120, 0)
            }
        )

        val callState = s?.callState ?: LinkService.CALL_IDLE
        callCard.visibility = if (running) View.VISIBLE else View.GONE
        callText.text = when (callState) {
            LinkService.CALL_RINGING -> "📞 Incoming call\n${s?.display()}"
            LinkService.CALL_OFFHOOK -> "In call: ${s?.display()}" +
                if (isRemote) "\n(audio is on the gateway phone)" else ""
            else -> "No active phone call"
        }
        answerBtn.visibility = if (callState == LinkService.CALL_RINGING) View.VISIBLE else View.GONE
        rejectBtn.visibility = if (callState == LinkService.CALL_RINGING) View.VISIBLE else View.GONE
        hangupBtn.visibility = if (callState == LinkService.CALL_OFFHOOK) View.VISIBLE else View.GONE

        dialSection.visibility = if (running && isRemote) View.VISIBLE else View.GONE
        gwSpeakerBtn.visibility = if (running && isRemote) View.VISIBLE else View.GONE
        gwSpeakerBtn.text = "Gateway speaker: " + if (gatewaySpeaker) "ON" else "OFF"

        voiceBtn.isEnabled = running
        voiceBtn.text = if (s?.voiceActive == true) "🔴 End voice link" else "🎙 Start voice link (talk phone ↔ phone)"
        speakerBtn.isEnabled = running
        speakerBtn.text = "This phone loudspeaker: " + if (s?.speakerOn != false) "ON" else "OFF"

        val isGateway = role == LinkService.ROLE_GATEWAY
        notifSection.visibility = if (isGateway) View.VISIBLE else View.GONE
        if (isGateway) {
            val on = NotifListener.isEnabled(this)
            val bound = NotifListener.instance != null
            notifStatus.text = when {
                !on -> "⚠️ Notification access is OFF.\nTap the button below and allow VGS.\n" +
                    "If VGS is greyed out / 'Restricted setting': open App info for VGS → ⋮ menu (top right) → " +
                    "'Allow restricted settings', then try again."
                !bound -> "⏳ Access is ON but the listener isn't connected yet.\n" +
                    "If this stays, turn VGS OFF and ON again in Notification access."
                else -> "✅ Notification sync is active"
            }
            notifStatus.setTextColor(
                when {
                    !on -> Color.rgb(200, 60, 0)
                    !bound -> Color.rgb(200, 120, 0)
                    else -> Color.rgb(0, 140, 60)
                }
            )
        }

        if (isRemote && loadedContactsVersion != Hub.contactsVersion) loadContacts()

        logView.text = Hub.logText()
    }

    /** Loads local + synced contacts in the background for the dial box suggestions. */
    private fun loadContacts() {
        loadedContactsVersion = Hub.contactsVersion
        Thread {
            val list = ContactStore.all(applicationContext)
            runOnUiThread { contactAdapter.all = list }
        }.start()
    }

    /** The number to call: from the picked suggestion, or digits typed by hand. */
    private fun numberToDial(): String {
        val t = dialInput.text.toString()
        val c = selectedContact
        if (c != null && t.trim() == c.label()) return c.number
        selectedContact = null
        return t.substringBefore("·").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        root.addView(label("VGS", 26f, bold = true))
        root.addView(label("Use a phone without internet like a smartwatch for a phone that has SIM/internet. Pair both phones in Bluetooth settings first.", 13f, Color.DKGRAY))

        root.addView(section("1. What is this phone?"))
        roleGroup = RadioGroup(this)
        rbGateway = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Gateway – this phone has the SIM / internet"
        }
        rbRemote = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Remote – watch mode (no internet)"
        }
        roleGroup.addView(rbGateway)
        roleGroup.addView(rbRemote)
        if (prefs.getString("role", LinkService.ROLE_REMOTE) == LinkService.ROLE_GATEWAY) {
            rbGateway.isChecked = true
        } else {
            rbRemote.isChecked = true
        }
        roleGroup.setOnCheckedChangeListener { _, _ -> refresh() }
        root.addView(roleGroup)

        deviceSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        deviceSection.addView(section("2. Choose the gateway phone"))
        deviceSpinner = Spinner(this)
        deviceSection.addView(deviceSpinner)
        deviceSection.addView(button("Refresh paired phones") { loadDevices() })
        root.addView(deviceSection)

        root.addView(section("Connection"))
        startBtn = button("Start") { startLink() }
        stopBtn = button("Stop") { stopLink() }
        root.addView(row(startBtn, stopBtn))
        statusText = label("● Stopped", 16f, bold = true)
        root.addView(statusText)

        callCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(236, 243, 255))
            }
        }
        callText = label("No active phone call", 18f, bold = true)
        callText.gravity = Gravity.CENTER
        callCard.addView(callText)
        answerBtn = button("Answer") { Hub.service?.answer() }
        answerBtn.setBackgroundColor(Color.rgb(30, 160, 80))
        answerBtn.setTextColor(Color.WHITE)
        rejectBtn = button("Reject") { Hub.service?.reject() }
        rejectBtn.setBackgroundColor(Color.rgb(210, 50, 50))
        rejectBtn.setTextColor(Color.WHITE)
        hangupBtn = button("Hang up") { Hub.service?.hangUp() }
        callCard.addView(row(rejectBtn, answerBtn))
        callCard.addView(hangupBtn)
        root.addView(callCard, lp(top = dp(12)))

        dialSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialSection.addView(section("Make a call through the gateway"))
        contactAdapter = ContactAdapter(this)
        dialInput = AutoCompleteTextView(this).apply {
            hint = "Type a name or number"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            threshold = 1
            setAdapter(contactAdapter)
            setOnItemClickListener { parent, _, position, _ ->
                selectedContact = parent.getItemAtPosition(position) as? Contact
                dialInput.setSelection(dialInput.text.length)
            }
        }
        dialSection.addView(dialInput)
        dialSection.addView(button("📞 Call") {
            val n = numberToDial()
            if (n.isEmpty()) toast("Pick a contact or enter a number") else Hub.service?.dial(n)
        })
        root.addView(dialSection)

        notifSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        notifSection.addView(section("Notification sync"))
        notifStatus = label("", 14f, Color.DKGRAY)
        notifSection.addView(notifStatus)
        mirrorSwitch = Switch(this).apply {
            text = "Send this phone's notifications to the remote"
            isChecked = prefs.getBoolean("mirror", true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("mirror", on).apply()
                Hub.log("Notification sync switch " + if (on) "ON" else "OFF")
            }
        }
        notifSection.addView(mirrorSwitch)
        notifSection.addView(button("Open notification access settings") {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                toast("Open Settings → Notifications → Notification access, and allow VGS")
            }
        })
        notifSection.addView(button("Open VGS App info (for 'Allow restricted settings')") {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", packageName, null))
                )
            } catch (e: Exception) {
                toast("Open Settings → Apps → VGS")
            }
        })
        notifSection.addView(button("Send test notification to remote") {
            val s = Hub.service
            if (s == null || !s.isActive) toast("Press Start first") else s.sendTestNotification()
        })
        root.addView(notifSection)

        root.addView(section("Voice link"))
        voiceBtn = button("Start voice link") { Hub.service?.toggleVoice() }
        root.addView(voiceBtn)
        speakerBtn = button("Loudspeaker") {
            val s = Hub.service
            if (s != null) s.setLocalSpeaker(!s.speakerOn)
        }
        root.addView(speakerBtn)
        gwSpeakerBtn = button("Gateway speaker") {
            gatewaySpeaker = !gatewaySpeaker
            Hub.service?.setGatewaySpeaker(gatewaySpeaker)
            refresh()
        }
        root.addView(gwSpeakerBtn)

        root.addView(section("Log"))
        logView = label("", 12f, Color.DKGRAY)
        logView.typeface = Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        root.addView(logView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = top }

    private fun label(t: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun section(t: String): TextView =
        label(t, 15f, Color.rgb(25, 80, 170), bold = true).apply { setPadding(0, dp(16), 0, dp(4)) }

    private fun button(t: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (v in views) {
                addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
