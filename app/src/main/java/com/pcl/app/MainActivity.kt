package com.pcl.app

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
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

private enum class MainTab { HOME, CALLS, UPDATE, SETTINGS }

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private var revision by mutableIntStateOf(0)
    private var role by mutableStateOf(LinkService.ROLE_GATEWAY)
    private var roleSelected by mutableStateOf(false)
    private var devices by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var selectedDevice by mutableStateOf<String?>(null)
    private var contacts by mutableStateOf<List<Contact>>(emptyList())
    private var loadedContactsVersion = -1
    /** A tab asked for by a notification (the missed-call one opens Calls). */
    private var requestedTab by mutableStateOf<MainTab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immersive full-screen: hide both system bars and allow Compose to use the display.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        prefs = Prefs.of(this)
        role = prefs.getString(Prefs.ROLE, LinkService.ROLE_GATEWAY) ?: LinkService.ROLE_GATEWAY
        roleSelected = true
        if (!prefs.contains(Prefs.ROLE)) prefs.edit().putString(Prefs.ROLE, LinkService.ROLE_GATEWAY).apply()
        selectedDevice = prefs.getString(Prefs.PEER_DEVICE, null)
        CallLogger.init(this)
        CallHistory.init(this)
        Favourites.init(this)
        AppUpdater.init(this, "N2K02/PCL", "PCL")
        handleOpenTab(intent)
        setContent { PCLTheme { AppScreen() } }
        loadDevices()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenTab(intent)
    }

    private fun handleOpenTab(intent: Intent?) {
        if (intent?.getStringExtra(LinkService.EXTRA_OPEN_TAB) == "calls") requestedTab = MainTab.CALLS
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
        if (role == LinkService.ROLE_REMOTE) loadContacts()
        refresh()
    }

    private fun corePerms(): List<String> {
        // PCL relays call control and status; Android manages cellular-call audio.
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun gatewayPerms(): List<String> = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE
    )

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
                missing.distinct().filter { !granted(it) }.toTypedArray(), 2
            )
            toast("Please allow the permissions, then press Start again")
            return
        }
        // Both roles need the other phone: the Remote connects to it, and the Gateway
        // accepts only it.
        val addr = selectedDevice
        if (addr == null || devices.none { it.second == addr }) {
            toast(
                if (chosenRole == LinkService.ROLE_REMOTE) "Select the paired Gateway phone on Home first"
                else "Select the paired Remote phone on Home first"
            )
            return
        }
        prefs.edit().putString(Prefs.ROLE, chosenRole).putString(Prefs.PEER_DEVICE, addr).apply()
        val i = Intent(this, LinkService::class.java)
            .setAction(LinkService.ACTION_START)
            .putExtra(LinkService.EXTRA_ROLE, chosenRole)
            .putExtra(LinkService.EXTRA_DEVICE, addr)
        startForegroundService(i)
        refresh()
    }

    private fun stopLink() {
        startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP))
        refresh()
    }

    private fun quitApp() {
        // Quitting also stops PCL from restarting itself after a reboot or update.
        prefs.edit().putBoolean(Prefs.LINK_ENABLED, false).apply()
        stopService(Intent(this, LinkService::class.java))
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
        // Never pick a device automatically: on a Gateway the choice decides which phone may connect.
        val saved = prefs.getString(Prefs.PEER_DEVICE, null)
        selectedDevice = saved?.takeIf { address -> devices.any { it.second == address } }
        revision++
    }

    private fun loadContacts() {
        loadedContactsVersion = Hub.contactsVersion
        Thread {
            val list = ContactStore.all(applicationContext)
            runOnUiThread { contacts = list }
        }.start()
    }

    private fun refresh() {
        revision++
        if (currentRole() == LinkService.ROLE_REMOTE && loadedContactsVersion != Hub.contactsVersion) loadContacts()
    }

    private fun formatTrafficRate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
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
            // Only the Gateway reads notifications; the Remote has no WhatsApp to watch.
            roleSelected && required.distinct().all { granted(it) } &&
                (role != LinkService.ROLE_GATEWAY || notificationAccess)
        }
        var screen by remember { mutableStateOf(if (setupReady) MainTab.HOME else MainTab.SETTINGS) }
        val unseenMissed by CallHistory.unseenMissed.collectAsState()
        // Calls is the Remote's tab; a notification may ask for it.
        LaunchedEffect(requestedTab, remote, setupReady) {
            requestedTab?.let { tab ->
                if (tab != MainTab.CALLS || (remote && setupReady)) screen = tab
                requestedTab = null
            }
        }
        LaunchedEffect(remote) { if (!remote && screen == MainTab.CALLS) screen = MainTab.HOME }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("PCL", fontWeight = FontWeight.Bold)
                            Text("Bluetooth Call Relay", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        FilledTonalIconButton(onClick = { quitApp() }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Quit")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = screen == MainTab.HOME, enabled = setupReady, onClick = { if (setupReady) screen = MainTab.HOME }, icon = { Icon(Icons.Default.Hub, null) }, label = { Text("Home") })
                    if (remote) {
                        NavigationBarItem(
                            selected = screen == MainTab.CALLS,
                            enabled = setupReady,
                            onClick = { if (setupReady) screen = MainTab.CALLS },
                            icon = {
                                BadgedBox(badge = { if (unseenMissed > 0) Badge { Text("$unseenMissed") } }) {
                                    Icon(Icons.Default.Call, null)
                                }
                            },
                            label = { Text("Calls") }
                        )
                    }
                    NavigationBarItem(selected = screen == MainTab.UPDATE, onClick = { screen = MainTab.UPDATE; AppUpdater.check(manual = true) }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Update") })
                    NavigationBarItem(selected = screen == MainTab.SETTINGS, onClick = { screen = MainTab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { insets ->
            if (screen == MainTab.CALLS && remote && setupReady) {
                CallsScreen(
                    ui = CallsUi(
                        connected = connected,
                        callState = callState,
                        callDisplay = service?.display().orEmpty(),
                        simStatus = service?.callControlError,
                        simOnGateway = service?.simCallOnGateway == true,
                        whatsappStatus = service?.whatsappCallStatus,
                        mirrorCall = service?.liveMirrorCall,
                        contacts = contacts,
                        gatewayVolume = service?.gatewayVolume ?: 0,
                        gatewayMaxVolume = service?.gatewayMaxVolume ?: 1,
                        gatewayAudioRoute = service?.gatewayAudioRoute ?: "UNKNOWN",
                    ),
                    actions = CallsActions(
                        simCall = { number, name -> service?.dial(number, name) },
                        whatsappCall = { number, name -> service?.startWhatsAppCall(number, name) },
                        answer = { service?.answer() },
                        reject = { service?.reject() },
                        hangUp = { service?.hangUp() },
                        pressMirror = { call, index, label -> service?.pressMirrorButton(call.key, index, label, call.pkg) },
                        syncContacts = { service?.syncContacts(); refresh() },
                        setGatewayVolume = { service?.requestGatewayVolume(it) },
                        setGatewayRoute = { service?.requestGatewayRoute(it) },
                        refreshGatewayAudio = { service?.refreshGatewayAudio() },
                    ),
                    modifier = Modifier.padding(insets),
                )
            } else Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (screen == MainTab.HOME && setupReady) {
                    SectionCard(
                        title = if (remote) "Gateway phone" else "Remote phone",
                        subtitle = if (remote) "Select the paired Gateway phone to connect to."
                            else "Select the paired Remote phone. Only this phone can connect to this Gateway."
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${devices.size} paired", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { loadDevices(); refresh() }) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(5.dp))
                                Text("Refresh")
                            }
                        }
                        if (devices.isEmpty()) {
                            Text("No paired devices. Pair the phones in Bluetooth settings, then refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(
                                onClick = { runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open Bluetooth settings")
                            }
                        } else {
                            devices.forEach { device ->
                                Card(
                                    onClick = {
                                        if (!running) {
                                            selectedDevice = device.second
                                            prefs.edit().putString(Prefs.PEER_DEVICE, device.second).apply()
                                            refresh()
                                        }
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (device.second == selectedDevice)
                                            MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Smartphone, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(12.dp))
                                        Column(
                                            Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(device.first, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                device.second,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (device.second == selectedDevice) {
                                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (screen == MainTab.HOME && setupReady) Text("Connection", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
                                Text(if (!running) "Press Start to connect" else if (connected) "Link active" else "Connecting to paired phone",
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
                        Text(
                            devices.firstOrNull { it.second == selectedDevice }?.let { peer ->
                                if (remote) "Gateway: ${peer.first}" else "Gateway · SIM calls use this phone · Remote: ${peer.first}"
                            } ?: if (remote) "Select the paired Gateway above." else "Select the paired Remote above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // The phones update separately; say so when they no longer match.
                        service?.versionWarning?.let { warning ->
                            Text(warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (screen == MainTab.HOME && setupReady) {
                    val traffic = service
                    SectionCard(title = "Link traffic", subtitle = "What PCL sends between the phones over Bluetooth") {
                        if (connected && traffic != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Download", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatTrafficRate(traffic.downloadBytesPerSecond), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Upload", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatTrafficRate(traffic.uploadBytesPerSecond), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else {
                            Text("Connect to view app traffic rates.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (screen == MainTab.SETTINGS) {
                    Text("Setup & settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Choose a role and grant every required permission. Home unlocks when setup is complete.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        RoleChoiceCard("Gateway", "SIM phone and call controller", Icons.Default.Radio, roleSelected && role == LinkService.ROLE_GATEWAY, Modifier.weight(1f)) {
                            if (!running) { role = LinkService.ROLE_GATEWAY; roleSelected = true; prefs.edit().putString(Prefs.ROLE, role).apply(); refresh() }
                        }
                        RoleChoiceCard("Remote", "Companion phone over Bluetooth", Icons.Default.Smartphone, roleSelected && role == LinkService.ROLE_REMOTE, Modifier.weight(1f)) {
                            if (!running) { role = LinkService.ROLE_REMOTE; roleSelected = true; prefs.edit().putString(Prefs.ROLE, role).apply(); refresh() }
                        }
                    }
                }


                if (screen == MainTab.HOME && setupReady && running) {
                    SectionCard(title = "Phone call", subtitle = if (remote) "Gateway SIM call status and controls" else "This phone’s SIM call status and controls") {
                        Text(
                            when (callState) {
                                LinkService.CALL_RINGING -> "Incoming SIM call"
                                LinkService.CALL_OFFHOOK -> if (service?.callDirection == "OUTGOING") "Outgoing SIM call in progress" else "Call in progress"
                                else -> "No active SIM call"
                            },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
                        )
                        if (callState == LinkService.CALL_RINGING || callState == LinkService.CALL_OFFHOOK) {
                            Text(service?.display().orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (remote && service?.simCallOnGateway == true) {
                            Text("Taken on the Gateway, so this phone stays quiet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val callError = service?.callControlError
                        if (callError?.startsWith("FAILED:") == true || callError?.startsWith("PENDING:") == true) {
                            Text(
                                callError.substringAfter(": ").ifBlank { "Call-control request status unavailable." },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (callError.startsWith("FAILED:")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (callState == LinkService.CALL_OFFHOOK && remote) {
                            Text("Call audio stays on the Gateway. Use its earpiece, loudspeaker or connected Bluetooth hands-free device.",
                                style = MaterialTheme.typography.bodySmall,
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

                if (screen == MainTab.HOME && setupReady && running && remote) {
                    // Dialing, recent calls and contacts live in the Calls tab.
                    OutlinedButton(onClick = { screen = MainTab.CALLS }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Open Calls: keypad, recents, contacts")
                    }
                }

                if (screen == MainTab.HOME && setupReady) ActivityLogCard()
                if (screen == MainTab.HOME && !setupReady) {
                    Text("Complete setup first", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    SectionCard(title = "Home locked", subtitle = "Grant the required permissions in Settings to enable the connection controls.") {
                        Button(onClick = { screen = MainTab.SETTINGS }, modifier = Modifier.fillMaxWidth()) { Text("Open Settings") }
                    }
                }
                if (screen == MainTab.UPDATE) {
                    Text("App updates", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    SectionCard(title = "PCL", subtitle = "Keep the app updated from the latest GitHub release.") {
                        Text("Installed version: ${AppUpdater.installedVersionName}")
                        val experimentalEnabled = AppUpdater.experimentalEnabled.collectAsState().value
                        androidx.compose.material3.Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Text("Experimental updates", modifier = Modifier.weight(1f))
                            androidx.compose.material3.Switch(checked = experimentalEnabled, onCheckedChange = AppUpdater::setExperimentalEnabled)
                        }
                        Text("Preview builds are opt-in; stable is the default.")

                        Text("Updates are checked against the latest GitHub release. APK verification and Android installation are handled by the updater.")
                        Button(onClick = { AppUpdater.check(manual = true) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Check for updates")
                        }
                    }
                    UpdateCard(modifier = Modifier.fillMaxWidth())
                }
                if (screen == MainTab.SETTINGS) {
                    SectionCard(title = "Permissions", subtitle = if (roleSelected) "Required access for the selected phone role." else "Select a role above to see its setup checklist.") {
                        if (roleSelected && role == LinkService.ROLE_GATEWAY) {
                            if (Build.VERSION.SDK_INT >= 33) PermissionSetupRow("Notifications", "Show the ongoing PCL status", granted(Manifest.permission.POST_NOTIFICATIONS)) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
                            PermissionSetupRow("Phone state", "Detect incoming and active SIM calls", granted(Manifest.permission.READ_PHONE_STATE)) { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1) }
                            PermissionSetupRow("Answer calls", "Allow call answering requests", granted(Manifest.permission.ANSWER_PHONE_CALLS)) { requestPermissions(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS), 1) }
                            PermissionSetupRow("Place calls", "Place calls through the gateway SIM", granted(Manifest.permission.CALL_PHONE)) { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1) }
                            PermissionSetupRow("Contacts (for sync)", "Send Gateway contacts to the Remote and resolve caller names", granted(Manifest.permission.READ_CONTACTS)) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1) }
                            PermissionSetupRow("Notification access", "Send notifications and WhatsApp calls to the Remote", notificationAccess, actionLabel = "Open settings") { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { toast("Open Android Settings → Notifications → Notification access") } }
                            val callHelperEnabled = WhatsappCallAccessibilityService.isEnabled(this@MainActivity)
                            PermissionSetupRow("WhatsApp calls (optional)", "Accessibility service \"PCL WhatsApp call helper\": lets PCL start the WhatsApp calls the Remote asks for", callHelperEnabled, actionLabel = "Open settings") {
                                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                    .onFailure { toast("Open Android Settings → Accessibility → Installed apps → PCL WhatsApp call helper") }
                            }
                            if (Build.VERSION.SDK_INT >= 31) PermissionSetupRow("Bluetooth connection", "Connect to the paired remote phone", granted(Manifest.permission.BLUETOOTH_CONNECT)) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
                        } else if (roleSelected) {
                            PermissionSetupRow("Local contacts (optional)", "Include this phone's contacts in Remote search", granted(Manifest.permission.READ_CONTACTS)) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1) }
                            if (Build.VERSION.SDK_INT >= 31) PermissionSetupRow("Bluetooth connection", "Find and connect to the paired gateway phone", granted(Manifest.permission.BLUETOOTH_CONNECT)) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
                            if (Build.VERSION.SDK_INT >= 33) PermissionSetupRow("Notifications", "Show the ongoing PCL status", granted(Manifest.permission.POST_NOTIFICATIONS)) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
                        } else {
                            Text("Choose Gateway or Remote above to load the matching permission checklist.")
                        }
                        // Android 13+ blocks these two for apps installed from an APK file
                        // until the user allows "restricted settings" in App info.
                        // Only the Gateway needs them; the Remote has neither.
                        if (roleSelected && role == LinkService.ROLE_GATEWAY && Build.VERSION.SDK_INT >= 33 &&
                            (!notificationAccess || !WhatsappCallAccessibilityService.isEnabled(this@MainActivity))
                        ) {
                            Text(
                                "Greyed out or \"Restricted setting\"? Android blocks Notification access and Accessibility for apps installed from an APK. Open App info, tap ⋮, choose Allow restricted settings, then try again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = {
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
                                }.onFailure { toast("Open Android Settings → Apps → PCL") }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Open App info") }
                        }
                    }
                    if (roleSelected) {
                        Card(colors = CardDefaults.cardColors(containerColor = if (setupReady) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (setupReady) "Setup complete" else "Setup incomplete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(if (setupReady) "All required permissions are granted. Home is enabled." else "Use Set up beside each item that needs attention. Home remains disabled until required permissions are ready.", style = MaterialTheme.typography.bodyMedium)
                                if (setupReady) Button(onClick = { screen = MainTab.HOME }, modifier = Modifier.fillMaxWidth()) { Text("Open Home") }
                            }
                        }
                        HorizontalDivider()
                        Text("Privacy", style = MaterialTheme.typography.titleMedium)
                        Text("PCL uses permissions for the selected phone role. Grant only the access needed for the features you want to use.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { roleSelected = false; prefs.edit().remove(Prefs.ROLE).apply(); screen = MainTab.SETTINGS; refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Reset device role") }
                    }
                    
                if (roleSelected && role == LinkService.ROLE_GATEWAY) {
                    SectionCard(title = "Notification sync", subtitle = "Calls sync automatically. Other notifications are optional.") {
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
                            catch (_: Exception) { toast("Open Settings → Notifications → Notification access, and allow PCL") }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open notification access settings") }
                        var mirror by remember { mutableStateOf(prefs.getBoolean(Prefs.MIRROR, false)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Send other notifications to remote")
                                Text("Messages and other apps. Calls always go to the Remote.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = mirror, onCheckedChange = {
                                mirror = it
                                prefs.edit().putBoolean(Prefs.MIRROR, it).apply()
                                if (!it) service?.clearOrdinaryMirrors()
                            })
                        }
                    }
                }

                    Text("PCL • Material 3 Expressive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
}

@Composable
private fun ActivityLogCard() {
    val context = LocalContext.current
    val lines by CallLogger.lines.collectAsState()
    SectionCard(title = "Activity logs", subtitle = "Latest ${lines.size}/100 lines. Newest entries appear first.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("PCL activity logs", lines.asReversed().joinToString("\n")))
                    CallLogger.log("Activity logs copied")
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Copy")
            }
            OutlinedButton(onClick = { CallLogger.clear() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Clear all")
            }
        }
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Hide log entries" else "Show log entries")
        }
        if (expanded) HorizontalDivider()
        if (expanded && lines.isEmpty()) {
            Text("No activity recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (expanded) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                lines.asReversed().forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RoleChoiceCard(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (selected) Text("Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PermissionSetupRow(title: String, description: String, ready: Boolean, actionLabel: String? = null, action: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (ready) "Granted" else "Not granted", style = MaterialTheme.typography.labelMedium, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = action, enabled = actionLabel != null || !ready) { Text(actionLabel ?: if (ready) "Granted" else "Allow") }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun PCLTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
