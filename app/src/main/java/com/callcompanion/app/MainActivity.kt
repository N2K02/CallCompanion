package com.callcompanion.app

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
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

private enum class MainTab { HOME, UPDATE, SETTINGS }

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private var revision by mutableIntStateOf(0)
    private var role by mutableStateOf(LinkService.ROLE_REMOTE)
    private var roleSelected by mutableStateOf(false)
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
        roleSelected = prefs.contains("role")
        selectedDevice = prefs.getString("device", null)
        Hub.clearLog()
        CallLogger.init(this)
        AppUpdater.init(this, "N2K02/CallCompanion", "CallCompanion")
        setContent { CallCompanionTheme { AppScreen() } }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
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

    private fun requestMissingPermissions() {
        val needed = corePerms() + if (role == LinkService.ROLE_GATEWAY) gatewayPerms() else emptyList()
        val missing = needed.distinct().filterNot { granted(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else refresh()
    }

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

    private fun quitApp() {
        stopService(Intent(this, LinkService::class.java))
        Hub.clearLog()
        CallLogger.clear()
        finishAndRemoveTask()
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = if (adapter == null ||
            (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
        ) emptyList() else try {
            (adapter.bondedDevices ?: emptySet()).map { (it.name?.takeIf(String::isNotBlank) ?: "Unnamed Bluetooth device") to it.address }.sortedBy { it.first.lowercase() }
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
        val setupReady = remember(revision, role, roleSelected, notificationAccess) {
            val required = corePerms() + if (role == LinkService.ROLE_GATEWAY) gatewayPerms() else emptyList()
            roleSelected && required.distinct().all { granted(it) } && (role != LinkService.ROLE_GATEWAY || notificationAccess)
        }
        var screen by remember { mutableStateOf(if (setupReady) MainTab.HOME else MainTab.SETTINGS) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("CallCompanion", fontWeight = FontWeight.Bold)
                            Text("Bluetooth Call Relay", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        OutlinedButton(onClick = { quitApp() }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(Modifier.width(5.dp))
                            Text("Quit")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = screen == MainTab.HOME, enabled = setupReady, onClick = { if (setupReady) screen = MainTab.HOME }, icon = { Icon(Icons.Default.Hub, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = screen == MainTab.UPDATE, onClick = { screen = MainTab.UPDATE; AppUpdater.check(manual = true) }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Update") })
                    NavigationBarItem(selected = screen == MainTab.SETTINGS, onClick = { screen = MainTab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (screen == MainTab.HOME && setupReady) Text("Your relay workspace", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (screen == MainTab.HOME && setupReady) Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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


            }
        }
    }
}

@Composable
private fun PermissionStatusRow(title: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(if (ready) "Granted" else "Needs setup", style = MaterialTheme.typography.bodySmall,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Icon(if (ready) Icons.Default.CheckCircle else Icons.Default.Settings, contentDescription = null,
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
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
