package com.callcompanion.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private var revision by mutableIntStateOf(0)
    private var role by mutableStateOf(LinkService.ROLE_REMOTE)
    private var devices by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var selectedDevice by mutableStateOf<String?>(null)
    private var contacts by mutableStateOf<List<Contact>>(emptyList())
    private var gatewaySpeaker by mutableStateOf(false)
    private var dialInput by mutableStateOf("")
    private var selectedContact by mutableStateOf<Contact?>(null)
    private var loadedContactsVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("callcompanion", MODE_PRIVATE)
        role = prefs.getString("role", LinkService.ROLE_REMOTE) ?: LinkService.ROLE_REMOTE
        selectedDevice = prefs.getString("device", null)
        CallLogger.init(this)
        AppUpdater.init(this, "N2K02/CallCompanion", "CallCompanion")
        setContent { CallCompanionTheme { AppScreen() } }
        requestPermissions(allPerms().toTypedArray(), 1)
        loadDevices()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Hub.listener = { refresh() }
        AppUpdater.onResume(this)
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

    private fun corePerms(): List<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun gatewayPerms(): List<String> = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE
    )

    private fun allPerms(): List<String> =
        corePerms() + gatewayPerms() + listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun currentRole(): String = Hub.service?.takeIf { it.isActive }?.role ?: role

    private fun startLink() {
        val chosenRole = role
        val needed = if (chosenRole == LinkService.ROLE_GATEWAY) corePerms() + gatewayPerms() else corePerms()
        val missing = needed.filter { !granted(it) }
        if (missing.isNotEmpty()) {
            requestPermissions(
                (missing + listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS))
                    .distinct().filter { !granted(it) }.toTypedArray(), 2
            )
            toast("Please allow the permissions, then press Start again")
            return
        }
        val i = Intent(this, LinkService::class.java)
            .setAction(LinkService.ACTION_START)
            .putExtra(LinkService.EXTRA_ROLE, chosenRole)
        if (chosenRole == LinkService.ROLE_REMOTE) {
            val addr = selectedDevice
            if (devices.none { it.second == addr } || addr == null) {
                toast("Pair with the gateway phone in Bluetooth settings first")
                return
            }
            prefs.edit().putString("device", addr).apply()
            i.putExtra(LinkService.EXTRA_DEVICE, addr)
        }
        prefs.edit().putString("role", chosenRole).apply()
        startForegroundService(i)
        refresh()
    }

    private fun stopLink() {
        startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP))
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = if (adapter == null ||
            (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
        ) emptyList() else try {
            (adapter.bondedDevices ?: emptySet()).map { (it.name ?: it.address) to it.address }
        } catch (_: SecurityException) { emptyList() }
        if (devices.none { it.second == selectedDevice }) selectedDevice = devices.firstOrNull()?.second
        revision++
    }

    private fun loadContacts() {
        loadedContactsVersion = Hub.contactsVersion
        Thread {
            val list = ContactStore.all(applicationContext)
            runOnUiThread { contacts = list }
        }.start()
    }

    private fun numberToDial(): String {
        val t = dialInput
        val c = selectedContact
        if (c != null && t.trim() == c.label()) return c.number
        selectedContact = null
        return t.substringBefore("·").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    private fun refresh() {
        revision++
        if (currentRole() == LinkService.ROLE_REMOTE && loadedContactsVersion != Hub.contactsVersion) loadContacts()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScreen() {
        val ignored = revision
        val service = Hub.service
        val running = service?.isActive == true
        val activeRole = if (running) service!!.role else role
        val remote = activeRole == LinkService.ROLE_REMOTE
        val connected = running && service?.connected == true
        val callState = service?.callState ?: LinkService.CALL_IDLE
        val notificationAccess = NotifListener.isEnabled(this)

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CallCompanion", fontWeight = FontWeight.Bold)
                            Text("Your phone, connected", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = { AppUpdater.check(manual = true) }) {
                            Icon(Icons.Default.Download, contentDescription = "Check for updates")
                        }
                        IconButton(onClick = { loadDevices(); refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
                        }
                    }
                )
            }
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(if (running) service?.connectionState ?: "Starting" else "Ready to connect",
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(if (!running) "Bluetooth link is stopped" else if (connected) "Connected to the other phone" else "Waiting for Bluetooth connection",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Icon(if (connected) Icons.Default.CheckCircle else Icons.Default.Sync,
                                contentDescription = null,
                                tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { startLink() }, enabled = !running, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Call, contentDescription = null)
                                Spacer(Modifier.width(8.dp)); Text("Start")
                            }
                            OutlinedButton(onClick = { stopLink() }, enabled = running, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp)); Text("Stop")
                            }
                        }
                    }
                }

                SectionCard(title = "Choose this phone's role", subtitle = "Use the same app on both phones.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = role == LinkService.ROLE_GATEWAY,
                            onClick = { if (!running) role = LinkService.ROLE_GATEWAY },
                            enabled = !running,
                            label = { Text("Gateway · SIM phone") },
                            leadingIcon = if (role == LinkService.ROLE_GATEWAY) ({ Icon(Icons.Default.CheckCircle, null) }) else null,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = role == LinkService.ROLE_REMOTE,
                            onClick = { if (!running) role = LinkService.ROLE_REMOTE },
                            enabled = !running,
                            label = { Text("Remote · watch mode") },
                            leadingIcon = if (role == LinkService.ROLE_REMOTE) ({ Icon(Icons.Default.CheckCircle, null) }) else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (remote && !running) {
                        Text("Paired gateway phone", style = MaterialTheme.typography.titleSmall)
                        var expanded by remember { mutableStateOf(false) }
                        val chosen = devices.firstOrNull { it.second == selectedDevice }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = chosen?.let { "\${it.first} (\${it.second})" } ?: "No paired phones found",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Bluetooth device") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                devices.forEach { device ->
                                    DropdownMenuItem(
                                        text = { Text("\${device.first} (\${device.second})") },
                                        onClick = { selectedDevice = device.second; expanded = false }
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { loadDevices() }) { Icon(Icons.Default.Refresh, null); Text("Refresh paired phones") }
                    }
                }

                if (running) {
                    SectionCard(title = "Phone call", subtitle = "Call controls are available while the link is running.") {
                        Text(
                            when (callState) {
                                LinkService.CALL_RINGING -> "Incoming call"
                                LinkService.CALL_OFFHOOK -> "Call in progress"
                                else -> "No active phone call"
                            },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
                        )
                        if (callState == LinkService.CALL_RINGING || callState == LinkService.CALL_OFFHOOK) {
                            Text(service?.display().orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (callState == LinkService.CALL_OFFHOOK && remote) {
                            Text("Audio is handled by the gateway phone.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (callState == LinkService.CALL_RINGING) {
                                Button(onClick = { service?.answer() }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Call, null); Spacer(Modifier.width(6.dp)); Text("Answer")
                                }
                                OutlinedButton(onClick = { service?.reject() }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text("Reject")
                                }
                            }
                            if (callState == LinkService.CALL_OFFHOOK) {
                                Button(onClick = { service?.hangUp() }, modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                    Icon(Icons.Default.CallEnd, null); Spacer(Modifier.width(8.dp)); Text("Hang up")
                                }
                            }
                        }
                    }
                }

                if (running && remote) {
                    SectionCard(title = "Make a call", subtitle = "Type a number or search synced contacts.") {
                        var suggestionsExpanded by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = dialInput,
                            onValueChange = { dialInput = it; selectedContact = null; suggestionsExpanded = it.isNotBlank() },
                            label = { Text("Contact name or phone number") },
                            leadingIcon = { Icon(Icons.Default.Call, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val digitsQuery = ContactStore.digits(dialInput)
                        val matches = if (dialInput.isBlank()) emptyList() else contacts.filter {
                            it.name.contains(dialInput, ignoreCase = true) ||
                                (digitsQuery.isNotEmpty() && ContactStore.digits(it.number).contains(digitsQuery))
                        }.take(6)
                        if (suggestionsExpanded && matches.isNotEmpty()) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column {
                                    matches.forEach { c ->
                                        TextButton(onClick = { selectedContact = c; dialInput = c.label(); suggestionsExpanded = false },
                                            modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.fillMaxWidth()) {
                                                Text(c.name.ifEmpty { c.number }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (c.name.isNotEmpty()) Text(c.number, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Button(onClick = {
                            val n = numberToDial()
                            if (n.isEmpty()) toast("Pick a contact or enter a number") else service?.dial(n)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Call through gateway")
                        }
                        FilledTonalButton(onClick = {
                            gatewaySpeaker = !gatewaySpeaker
                            service?.setGatewaySpeaker(gatewaySpeaker)
                            refresh()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(8.dp))
                            Text("Gateway speaker: " + if (gatewaySpeaker) "ON" else "OFF")
                        }
                    }
                }

                if (activeRole == LinkService.ROLE_GATEWAY) {
                    SectionCard(title = "Notification sync", subtitle = "Mirror notifications to the remote phone.") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Notification access")
                                Text(if (notificationAccess) "Enabled" else "Not enabled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (notificationAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                            Icon(if (notificationAccess) Icons.Default.CheckCircle else Icons.Default.Settings,
                                contentDescription = null,
                                tint = if (notificationAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(onClick = {
                            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                            catch (_: Exception) { toast("Open Settings → Notifications → Notification access, and allow CallCompanion") }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open notification access settings") }
                        var mirror by remember { mutableStateOf(prefs.getBoolean("mirror", true)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Send notifications to remote", Modifier.weight(1f))
                            Switch(checked = mirror, onCheckedChange = {
                                mirror = it
                                prefs.edit().putBoolean("mirror", it).apply()
                            })
                        }
                    }
                }

                SectionCard(title = "Voice link", subtitle = "Talk between the two phones over Bluetooth.") {
                    Button(onClick = { service?.toggleVoice(); refresh() }, enabled = running, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp))
                        Text(if (service?.voiceActive == true) "End voice link" else "Start voice link")
                    }
                    FilledTonalButton(onClick = {
                        service?.let { it.setLocalSpeaker(!it.speakerOn); refresh() }
                    }, enabled = running, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(8.dp))
                        Text("This phone loudspeaker: " + if (service?.speakerOn != false) "ON" else "OFF")
                    }
                }

                SectionCard(title = "Activity log", subtitle = "Recent connection and service events.") {
                    Text(Hub.logText().ifBlank { "No events yet. Start the link to see connection status here." },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    )
                }
                UpdateCard(modifier = Modifier.fillMaxWidth())
                Text("CallCompanion • Material 3 Expressive", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun CallCompanionTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFB8C7FF), secondary = Color(0xFFBFC6E5))
        else -> lightColorScheme(primary = Color(0xFF4355B9), secondary = Color(0xFF5A5D72))
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp)
        ),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        ),
        content = content
    )
}
