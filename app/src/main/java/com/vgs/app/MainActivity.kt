@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vgs.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Pixel-style Material 3 Expressive theme: wallpaper colors + system light/dark mode. */
@Composable
fun VgsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    // Compose state
    private var tick by mutableIntStateOf(0)
    private var role by mutableStateOf(LinkService.ROLE_REMOTE)
    private var devices by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var selectedDevice by mutableStateOf<String?>(null)
    private var contacts by mutableStateOf<List<Contact>>(emptyList())
    private var mirrorOn by mutableStateOf(true)
    private var gatewaySpeaker by mutableStateOf(false)
    private var loadedContactsVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vgs", MODE_PRIVATE)
        role = prefs.getString("role", LinkService.ROLE_REMOTE) ?: LinkService.ROLE_REMOTE
        selectedDevice = prefs.getString("device", null)
        mirrorOn = prefs.getBoolean("mirror", true)
        setContent { VgsTheme { Screen() } }
        requestPermissions(allPerms().toTypedArray(), 1)
        loadDevices()
        loadContacts()
    }

    override fun onResume() {
        super.onResume()
        Hub.listener = {
            tick++
            if (Hub.contactsVersion != loadedContactsVersion) loadContacts()
        }
        if (role == LinkService.ROLE_GATEWAY) NotifListener.rebind(this)
        tick++
    }

    override fun onPause() {
        Hub.listener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
        loadContacts()
        tick++
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

    private fun startLink() {
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
            val addr = selectedDevice
            if (addr == null || devices.none { it.second == addr }) {
                toast("Choose the gateway phone first (pair it in Bluetooth settings)")
                return
            }
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
        if (selectedDevice == null && devices.size == 1) selectedDevice = devices[0].second
    }

    private fun loadContacts() {
        loadedContactsVersion = Hub.contactsVersion
        Thread {
            val list = ContactStore.all(applicationContext)
            runOnUiThread { contacts = list }
        }.start()
    }

    private fun openSettings(action: String, withPackage: Boolean = false) {
        try {
            val i = Intent(action)
            if (withPackage) i.data = Uri.fromParts("package", packageName, null)
            startActivity(i)
        } catch (e: Exception) {
            toast("Couldn't open settings")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- UI

    @Composable
    private fun Screen() {
        @Suppress("UNUSED_VARIABLE")
        val t = tick // re-read service state whenever the service reports a change
        val s = Hub.service
        val running = s != null && s.isActive
        val effRole = if (running) s!!.role else role
        val isRemote = effRole == LinkService.ROLE_REMOTE
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text("VGS", fontWeight = FontWeight.Bold) },
                    scrollBehavior = scroll
                )
            }
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RoleCard(running, effRole)
                if (isRemote && !running) DeviceCard()
                ConnectionCard(s, running)
                if (running) CallCard(s!!, isRemote)
                if (running && isRemote) DialCard(s!!)
                if (effRole == LinkService.ROLE_GATEWAY) NotificationCard(s, running)
                if (running) VoiceCard(s!!, isRemote)
                LogCard()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                content()
            }
        }
    }

    @Composable
    private fun RoleCard(running: Boolean, effRole: String) {
        SectionCard("This phone") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(LinkService.ROLE_GATEWAY to "Gateway", LinkService.ROLE_REMOTE to "Remote")
                options.forEachIndexed { i, (r, label) ->
                    SegmentedButton(
                        selected = effRole == r,
                        onClick = {
                            role = r
                            prefs.edit().putString("role", r).apply()
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        enabled = !running
                    ) { Text(label) }
                }
            }
            Text(
                if (effRole == LinkService.ROLE_GATEWAY) "Has the SIM / internet. Sends calls and notifications to the remote."
                else "Watch mode: no internet. Rings, answers and dials through the gateway.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun DeviceCard() {
        SectionCard("Gateway phone") {
            if (devices.isEmpty()) {
                Text(
                    "No paired phones. Pair both phones in Android Bluetooth settings first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            devices.forEach { (name, addr) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { selectedDevice = addr }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedDevice == addr, onClick = { selectedDevice = addr })
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(addr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = { loadDevices() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refresh paired phones")
            }
        }
    }

    @Composable
    private fun ConnectionCard(s: LinkService?, running: Boolean) {
        SectionCard("Connection") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !running -> Dot(MaterialTheme.colorScheme.outline)
                    s!!.connected -> Dot(MaterialTheme.colorScheme.primary)
                    else -> LoadingIndicator(Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (!running) "Stopped" else s!!.connectionState,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { startLink() },
                    enabled = !running,
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text("Start") }
                OutlinedButton(
                    onClick = { stopLink() },
                    enabled = running,
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text("Stop") }
            }
        }
    }

    @Composable
    private fun Dot(color: Color) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
    }

    @Composable
    private fun CallCard(s: LinkService, isRemote: Boolean) {
        val state = s.callState
        val ringing = state == LinkService.CALL_RINGING
        val inCall = state == LinkService.CALL_OFFHOOK
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (ringing || inCall) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (ringing || inCall) Icons.Filled.Call else Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    when {
                        ringing -> "Incoming call"
                        inCall -> "In call"
                        else -> "No active phone call"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
                if (ringing || inCall) {
                    Text(s.display(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                }
                if (inCall && isRemote) {
                    Text(
                        "Audio is on the gateway phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (ringing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { s.reject() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Reject")
                        }
                        Button(
                            onClick = { s.answer() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E8E3E),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Answer")
                        }
                    }
                }
                if (inCall) {
                    Button(
                        onClick = { s.hangUp() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) { Text("Hang up") }
                }
            }
        }
    }

    @Composable
    private fun DialCard(s: LinkService) {
        var query by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf<Contact?>(null) }
        val suggestions = remember(query, contacts, picked) {
            if (picked != null && picked!!.label() == query) emptyList()
            else ContactStore.search(contacts, query)
        }
        SectionCard("Make a call") {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (picked?.label() != it) picked = null
                },
                label = { Text("Name or number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (suggestions.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    suggestions.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = c
                                    query = c.label()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(c.name.ifEmpty { c.number }, style = MaterialTheme.typography.bodyLarge)
                                Text(c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val c = picked
                    val n = if (c != null && c.label() == query.trim()) c.number
                    else query.substringBefore("·").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    if (n.isEmpty()) toast("Pick a contact or enter a number") else s.dial(n)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Call")
            }
            Text(
                "${contacts.size} contacts available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun NotificationCard(s: LinkService?, running: Boolean) {
        val enabled = NotifListener.isEnabled(this)
        val bound = NotifListener.instance != null
        SectionCard("Notification sync") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = when {
                        !enabled -> MaterialTheme.colorScheme.error
                        !bound -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    when {
                        !enabled -> "Notification access is OFF"
                        !bound -> "Access ON – listener not connected yet"
                        else -> "Notification sync is active"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (!enabled) {
                Text(
                    "Allow VGS in Notification access. If it's greyed out (\"Restricted setting\"): open App info → ⋮ (top right) → Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!bound) {
                Text(
                    "If this stays, turn VGS off and on again in Notification access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SwitchRow("Send notifications to the remote", mirrorOn) { on ->
                mirrorOn = on
                prefs.edit().putBoolean("mirror", on).apply()
                Hub.log("Notification sync switch " + if (on) "ON" else "OFF")
            }
            FilledTonalButton(
                onClick = { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Notification access settings")
            }
            OutlinedButton(
                onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("App info (Allow restricted settings)") }
            OutlinedButton(
                onClick = {
                    if (s == null || !running) toast("Press Start first") else s.sendTestNotification()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send test notification to remote") }
        }
    }

    @Composable
    private fun VoiceCard(s: LinkService, isRemote: Boolean) {
        SectionCard("Voice link") {
            Text(
                "Talk live between the two phones over Bluetooth.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (s.voiceActive) {
                Button(
                    onClick = { s.toggleVoice() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("End voice link") }
            } else {
                FilledTonalButton(
                    onClick = { s.toggleVoice() },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("Start voice link") }
            }
            SwitchRow("This phone's loudspeaker", s.speakerOn) { on -> s.setLocalSpeaker(on) }
            if (isRemote) {
                SwitchRow("Gateway loudspeaker (during calls)", gatewaySpeaker) { on ->
                    gatewaySpeaker = on
                    s.setGatewaySpeaker(on)
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onChange(!checked) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun LogCard() {
        SectionCard("Log") {
            val text = Hub.logText()
            Text(
                text.ifEmpty { "Nothing yet" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
