@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.pcl.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.telephony.PhoneNumberUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.Normalizer
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What the Calls tab shows. Read from [LinkService] by MainActivity on every refresh. */
class CallsUi(
    val connected: Boolean,
    val callState: String,
    val callDisplay: String,
    val simStatus: String?,
    /** The SIM call was answered or placed on the Gateway itself. */
    val simOnGateway: Boolean,
    val whatsappStatus: String?,
    /** A WhatsApp (or other app's) call on the Gateway that this phone shows. */
    val mirrorCall: LinkService.MirrorCall?,
    val contacts: List<Contact>,
    val gatewayVolume: Int,
    val gatewayMaxVolume: Int,
    val gatewayAudioRoute: String,
)

class CallsActions(
    val simCall: (number: String, name: String) -> Unit,
    val whatsappCall: (number: String, name: String) -> Unit,
    val answer: () -> Unit,
    val reject: () -> Unit,
    val hangUp: () -> Unit,
    /** Press one of a Gateway app call's buttons: its index on the Gateway and its label. */
    val pressMirror: (call: LinkService.MirrorCall, index: Int, label: String) -> Unit,
    val syncContacts: () -> Unit,
    val setGatewayVolume: (Int) -> Unit,
    val setGatewayRoute: (String) -> Unit,
    val refreshGatewayAudio: () -> Unit,
)

private enum class CallsSection(val label: String, val icon: ImageVector) {
    KEYPAD("Keypad", Icons.Default.Dialpad),
    RECENTS("Recents", Icons.Default.History),
    CONTACTS("Contacts", Icons.Default.Contacts),
}

/** Green that reads as "call" in both light and dark themes. */
private val CallGreen = Color(0xFF1E8E3E)

/**
 * Remote: the Calls tab. A dial keypad with smart search (type 5-6-2 to find "Joan"),
 * recent calls through the Gateway, contacts with favourites, and the live call on top.
 */
@Composable
fun CallsScreen(ui: CallsUi, actions: CallsActions, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableStateOf(CallsSection.KEYPAD) }
    var number by rememberSaveable { mutableStateOf("") }
    val unseenMissed by CallHistory.unseenMissed.collectAsState()
    val names = remember(ui.contacts) { NameBook(ui.contacts) }

    // Opening Recents counts as having seen the missed calls.
    LaunchedEffect(section, unseenMissed) {
        if (section == CallsSection.RECENTS && unseenMissed > 0) CallHistory.markMissedSeen()
    }

    val edit: (String) -> Unit = { n -> number = n; section = CallsSection.KEYPAD }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val mirror = ui.mirrorCall
        if (ui.callState != LinkService.CALL_IDLE) {
            ActiveCallCard(ui, actions)
            Spacer(Modifier.height(10.dp))
        } else if (mirror != null) {
            AppCallCard(mirror, ui, actions)
            Spacer(Modifier.height(10.dp))
        } else if (!ui.connected) {
            Text(
                "Not connected to the Gateway. You can browse calls and contacts; calling needs the link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            CallsSection.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = section == s,
                    onClick = { section = s },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = CallsSection.entries.size),
                    icon = {
                        if (s == CallsSection.RECENTS && unseenMissed > 0) {
                            BadgedBox(badge = { Badge { Text("$unseenMissed") } }) {
                                Icon(s.icon, null, Modifier.size(18.dp))
                            }
                        } else {
                            Icon(s.icon, null, Modifier.size(18.dp))
                        }
                    },
                    label = { Text(s.label, maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                CallsSection.KEYPAD -> KeypadPane(ui, actions, names, number, onNumber = { number = it })
                CallsSection.RECENTS -> RecentsPane(ui, actions, names, onEdit = edit)
                CallsSection.CONTACTS -> ContactsPane(ui, actions, onEdit = edit)
            }
        }
    }
}

// ---------------------------------------------------------------- live call

@Composable
private fun ActiveCallCard(ui: CallsUi, actions: CallsActions) {
    val ringing = ui.callState == LinkService.CALL_RINGING
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(ui.callState) {
        while (ui.callState == LinkService.CALL_OFFHOOK) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val since = CallHistory.connectedSince
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ringing) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneInTalk, null, Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            ringing -> "Incoming SIM call"
                            ui.simOnGateway -> "Taken on the Gateway"
                            since > 0 -> "On a call · ${duration((now - since) / 1000)}"
                            else -> "Calling…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(ui.callDisplay, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (ui.connected) {
                LaunchedEffect(ui.callState) {
                    while (true) {
                        actions.refreshGatewayAudio()
                        delay(3000)
                    }
                }
                Text("Gateway volume: " + ui.gatewayVolume + " / " + ui.gatewayMaxVolume)
                Slider(value = ui.gatewayVolume.coerceIn(0, ui.gatewayMaxVolume).toFloat(),
                    onValueChange = { actions.setGatewayVolume(it.toInt()) },
                    valueRange = 0f..ui.gatewayMaxVolume.coerceAtLeast(1).toFloat(),
                    steps = (ui.gatewayMaxVolume - 1).coerceAtLeast(0))
                Text("SIM audio output: " + ui.gatewayAudioRoute)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.setGatewayRoute("EARPIECE") },
                        enabled = ui.callState == LinkService.CALL_OFFHOOK) { Text("Earpiece") }
                    OutlinedButton(onClick = { actions.setGatewayRoute("SPEAKER") },
                        enabled = ui.callState == LinkService.CALL_OFFHOOK) { Text("Speaker") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (ringing) {
                    FilledTonalButton(
                        onClick = actions.reject,
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Default.CallEnd, null); Spacer(Modifier.width(6.dp)); Text("Decline") }
                    FilledTonalButton(
                        onClick = actions.answer,
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = CallGreen, contentColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(6.dp)); Text("Answer") }
                } else {
                    FilledTonalButton(
                        onClick = actions.hangUp,
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(Icons.Default.CallEnd, null); Spacer(Modifier.width(6.dp)); Text("Hang up") }
                }
            }
            Text(
                "Talk through the Gateway or its Bluetooth headset.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** A WhatsApp (or other app's) call on the Gateway, with that call's own buttons. */
@Composable
private fun AppCallCard(call: LinkService.MirrorCall, ui: CallsUi, actions: CallsActions) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.connectedAt) {
        while (call.connectedAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (call.ringing) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            call.ringing -> "Incoming ${call.app} call"
                            call.connectedAt != null -> "${call.app} call · ${duration(((now - call.connectedAt) / 1000).coerceAtLeast(0))}"
                            else -> "${call.app} call"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(call.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (call.buttons.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    call.buttons.forEach { (index, label) ->
                        val answer = WhatsAppCalls.isAnswer(label)
                        val end = WhatsAppCalls.isDecline(label) || WhatsAppCalls.isEnd(label)
                        FilledTonalButton(
                            onClick = { actions.pressMirror(call, index, label) },
                            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                containerColor = when {
                                    answer -> CallGreen
                                    end -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = when {
                                    answer -> Color.White
                                    end -> MaterialTheme.colorScheme.onError
                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (answer || end) {
                                Icon(if (answer) Icons.Default.Call else Icons.Default.CallEnd, null)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }
            LaunchedEffect(call.key) {
                while (true) {
                    actions.refreshGatewayAudio()
                    delay(3000)
                }
            }
            Text("Gateway call volume: " + ui.gatewayVolume + " / " + ui.gatewayMaxVolume)
            Slider(
                value = ui.gatewayVolume.coerceIn(0, ui.gatewayMaxVolume).toFloat(),
                onValueChange = { actions.setGatewayVolume(it.toInt()) },
                valueRange = 0f..ui.gatewayMaxVolume.coerceAtLeast(1).toFloat(),
                steps = (ui.gatewayMaxVolume - 1).coerceAtLeast(0),
                enabled = ui.connected,
            )
            if (call.pkg.startsWith("com.whatsapp") && !call.ringing && ui.connected) {
                Text("Experimental audio route · " + ui.gatewayAudioRoute)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.setGatewayRoute("EARPIECE") }) { Text("Earpiece") }
                    OutlinedButton(onClick = { actions.setGatewayRoute("SPEAKER") }) { Text("Speaker") }
                }
                Text("WhatsApp may override the selected route.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "The call runs on the Gateway: talk through its earpiece or Bluetooth headset.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------------------------------------------------------- keypad

private val KEYS = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

@Composable
private fun KeypadPane(
    ui: CallsUi,
    actions: CallsActions,
    names: NameBook,
    number: String,
    onNumber: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val favourites by Favourites.list.collectAsState()

    // Key tones like a phone's dial pad, only when the ringer is on.
    val tones = remember { runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 60) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tones?.release() } }
    val audio = remember { context.getSystemService(AudioManager::class.java) }

    fun press(key: String) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            toneFor(key)?.let { tone -> runCatching { tones?.startTone(tone, 120) } }
        }
        if (number.length < 32) onNumber(number + key)
    }

    val match = names.exact(number)
    val suggestions = remember(number, names) { if (match != null) emptyList() else names.smartSearch(number, 2) }
    val name = match?.name.orEmpty()

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top area: favourites when nothing is typed, otherwise matching contacts.
            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.BottomCenter) {
                if (number.isEmpty() && favourites.isNotEmpty()) {
                    FavouriteStrip(favourites) { c -> onNumber(c.number) }
                } else if (suggestions.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        suggestions.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onNumber(ContactStore.digits(c.number).let { d -> if (c.number.trim().startsWith("+")) "+$d" else d }) }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(c.name.ifEmpty { c.number }, 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name.ifEmpty { c.number }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    if (c.name.isNotEmpty()) Text(c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // The number, with the contact it belongs to. Long-press to paste.
            Text(
                if (number.isEmpty()) "Enter a number" else formatNumber(number),
                style = when {
                    number.isEmpty() -> MaterialTheme.typography.headlineSmall
                    number.length <= 12 -> MaterialTheme.typography.displaySmall
                    number.length <= 16 -> MaterialTheme.typography.headlineLarge
                    else -> MaterialTheme.typography.headlineSmall
                },
                color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                            .filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                        if (pasted.isNotEmpty()) onNumber(pasted.take(32))
                    },
                ).padding(top = 8.dp),
            )
            Text(
                when {
                    name.isNotEmpty() -> name
                    number.isEmpty() -> "Long-press here to paste a number"
                    else -> " "
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            StatusLine(ui)
            Spacer(Modifier.height(8.dp))

            // Keys fill all the space left, like the Google Dialer: 4 rows of keys + the call row.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
                val hGap = 14.dp
                val vGap = 8.dp
                val byWidth = (maxWidth - hGap * 2) / 3
                val byHeight = (maxHeight - vGap * 4) / 5
                val key = minOf(byWidth, byHeight, 112.dp).coerceAtLeast(48.dp)

                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(vGap, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    KEYS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(hGap)) {
                            row.forEach { (digit, letters) ->
                                DialKey(digit, letters, key,
                                    onClick = { press(digit) },
                                    onLongClick = if (digit == "0") ({ press("+") }) else null,
                                )
                            }
                        }
                    }

                    // WhatsApp · Call · Delete, in the same three columns as the keys.
                    Row(horizontalArrangement = Arrangement.spacedBy(hGap), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(key), contentAlignment = Alignment.Center) {
                            FilledTonalIconButton(
                                onClick = { actions.whatsappCall(number, name) },
                                enabled = ui.connected && number.isNotEmpty() && ui.whatsappStatus?.startsWith("PENDING:") != true,
                                modifier = Modifier.size(key * 0.72f),
                            ) { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "WhatsApp call on the Gateway") }
                        }
                        FilledTonalIconButton(
                            onClick = { actions.simCall(number, name) },
                            enabled = ui.connected && number.isNotEmpty() && ui.callState == LinkService.CALL_IDLE,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CallGreen, contentColor = Color.White),
                            modifier = Modifier.size(key),
                        ) { Icon(Icons.Default.Call, contentDescription = "Call through the Gateway SIM", Modifier.size(key * 0.4f)) }
                        Box(
                            Modifier.size(key).clip(CircleShape).combinedClickable(
                                enabled = number.isNotEmpty(),
                                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNumber(number.dropLast(1)) },
                                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onNumber("") },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (number.isNotEmpty()) Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete (hold to clear)", Modifier.size(key * 0.3f))
                        }
                    }
                }
            }
        }
}

@Composable
private fun DialKey(digit: String, letters: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(size).clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(digit, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Normal, lineHeight = (size.value * 0.46f).sp)
            if (letters.isNotEmpty()) {
                Text(letters, fontSize = (size.value * 0.14f).coerceAtLeast(9f).sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FavouriteStrip(favourites: List<Contact>, onPick: (Contact) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Favourites", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(favourites, key = { Favourites.key(it.number) }) { c ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp).clip(MaterialTheme.shapes.medium).clickable { onPick(c) }.padding(4.dp),
                ) {
                    Avatar(c.name.ifEmpty { c.number }, 48.dp)
                    Text(
                        c.name.ifEmpty { c.number }.substringBefore(' '),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The last SIM or WhatsApp request's result, so a failed call isn't silent. */
@Composable
private fun StatusLine(ui: CallsUi) {
    val sim = ui.simStatus?.takeIf { it.startsWith("FAILED:") || it.startsWith("PENDING:") }
    val status = sim ?: ui.whatsappStatus
    AnimatedVisibility(status != null) {
        val s = status.orEmpty()
        Text(
            s.substringAfter(": "),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = when {
                s.startsWith("FAILED:") -> MaterialTheme.colorScheme.error
                s.startsWith("OK:") -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------- recents

@Composable
private fun RecentsPane(ui: CallsUi, actions: CallsActions, names: NameBook, onEdit: (String) -> Unit) {
    val records by CallHistory.records.collectAsState()
    var expanded by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    if (records.isEmpty()) {
        EmptyState(Icons.Default.History, "No calls yet", "Calls you make or get through the Gateway show up here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${records.size} calls", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Clear")
                }
            }
        }
        var lastDay = ""
        records.forEachIndexed { index, r ->
            val day = dayLabel(r.at)
            if (day != lastDay) {
                lastDay = day
                item(key = "day-$index") {
                    Text(day, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
            }
            item(key = "call-$index-${r.at}") {
                val name = r.name.ifEmpty { names.exact(r.number)?.name.orEmpty() }
                PersonRow(
                    title = name.ifEmpty { r.number.ifEmpty { "Unknown number" } },
                    titleColor = if (r.type == CallHistory.Type.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    subtitle = buildString {
                        if (r.whatsapp) append("WhatsApp · ")
                        append(typeLabel(r.type))
                        append(" · ")
                        append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.at)))
                        if (r.durationSec > 0) append(" · ").append(duration(r.durationSec))
                    },
                    leading = {
                        Icon(
                            typeIcon(r.type), contentDescription = typeLabel(r.type),
                            tint = if (r.type == CallHistory.Type.MISSED || r.type == CallHistory.Type.DECLINED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    number = r.number,
                    name = name,
                    expanded = expanded == index,
                    onToggle = { expanded = if (expanded == index) null else index },
                    ui = ui,
                    actions = actions,
                    onEdit = onEdit,
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear call history?") },
            text = { Text("This removes the list on this phone only. The Gateway's own call log isn't touched.") },
            confirmButton = { TextButton(onClick = { CallHistory.clear(); confirmClear = false; expanded = null }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- contacts

@Composable
private fun ContactsPane(ui: CallsUi, actions: CallsActions, onEdit: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val favourites by Favourites.list.collectAsState()
    val shown = remember(query, ui.contacts) {
        val q = query.trim()
        val qd = ContactStore.digits(q)
        if (q.isEmpty()) ui.contacts else ui.contacts.filter {
            it.name.contains(q, ignoreCase = true) || (qd.isNotEmpty() && ContactStore.digits(it.number).contains(qd))
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search ${ui.contacts.size} contacts") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
                } else {
                    IconButton(onClick = actions.syncContacts, enabled = ui.connected) { Icon(Icons.Default.Sync, "Sync contacts from the Gateway") }
                }
            },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui.contacts.isEmpty()) {
            EmptyState(Icons.Default.Contacts, "No contacts yet", "Tap the sync button to copy the Gateway's contacts to this phone.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            if (query.isEmpty() && favourites.isNotEmpty()) {
                item { SectionLabel("Favourites") }
                items(favourites, key = { "fav-" + Favourites.key(it.number) }) { c ->
                    ContactRow(c, expanded, { expanded = it }, ui, actions, onEdit, prefix = "fav-")
                }
                item { SectionLabel("All contacts") }
            }
            items(shown, key = { "all-" + it.number + it.name }) { c ->
                ContactRow(c, expanded, { expanded = it }, ui, actions, onEdit, prefix = "all-")
            }
        }
    }
}

@Composable
private fun ContactRow(
    c: Contact,
    expanded: String?,
    setExpanded: (String?) -> Unit,
    ui: CallsUi,
    actions: CallsActions,
    onEdit: (String) -> Unit,
    prefix: String,
) {
    val id = prefix + c.number + c.name
    PersonRow(
        title = c.name.ifEmpty { c.number },
        subtitle = if (c.name.isEmpty()) "" else c.number,
        leading = { Avatar(c.name.ifEmpty { c.number }, 40.dp) },
        number = c.number,
        name = c.name,
        expanded = expanded == id,
        onToggle = { setExpanded(if (expanded == id) null else id) },
        ui = ui,
        actions = actions,
        onEdit = onEdit,
    )
}

// ---------------------------------------------------------------- shared rows

/** A person or call: tap to show Call / WhatsApp / Favourite / Edit; the phone icon calls at once. */
@Composable
private fun PersonRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    number: String,
    name: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    ui: CallsUi,
    actions: CallsActions,
    onEdit: (String) -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val canCall = ui.connected && number.isNotEmpty() && ui.callState == LinkService.CALL_IDLE
    val favourite = Favourites.list.collectAsState().value.any { Favourites.key(it.number) == Favourites.key(number) }
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                    if (favourite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { actions.simCall(number, name) }, enabled = canCall) {
                Icon(Icons.Default.Call, contentDescription = "Call $title through the Gateway SIM", tint = if (canCall) CallGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(expanded) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 52.dp, top = 6.dp),
            ) {
                ChipButton(Icons.AutoMirrored.Filled.Chat, "WhatsApp", enabled = ui.connected && number.isNotEmpty()) { actions.whatsappCall(number, name) }
                ChipButton(if (favourite) Icons.Default.Star else Icons.Default.StarBorder, if (favourite) "Unstar" else "Favourite", enabled = number.isNotEmpty()) {
                    Favourites.toggle(Contact(name, number))
                }
                ChipButton(Icons.Default.Edit, "Edit", enabled = number.isNotEmpty()) { onEdit(number) }
            }
        }
    }
}

@Composable
private fun ChipButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val (bg, fg) = colors[(name.hashCode() and 0x7fffffff) % colors.size]
    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
    Surface(shape = CircleShape, color = bg, contentColor = fg, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(initial, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- helpers

/** Contacts looked up by number (any format) and by keypad letters. */
private class NameBook(contacts: List<Contact>) {
    private val byNumber = HashMap<String, Contact>()
    private val entries: List<Pair<Contact, List<String>>>

    init {
        contacts.forEach { c -> Favourites.key(c.number).takeIf { it.length >= 6 }?.let { byNumber.putIfAbsent(it, c) } }
        entries = contacts.map { c -> c to c.name.split(' ', '-', '.').filter { it.isNotBlank() }.map(::t9) }
    }

    fun exact(number: String): Contact? = Favourites.key(number).takeIf { it.length >= 6 }?.let { byNumber[it] }

    /** Typing 5-6-2 finds "Joan" (J=5, O=6, A=2); digits also match inside numbers. */
    fun smartSearch(typed: String, limit: Int): List<Contact> {
        val q = typed.filter { it.isDigit() }
        if (q.length < 2) return emptyList()
        val byName = entries.filter { (_, words) -> words.any { it.startsWith(q) } || words.joinToString("").startsWith(q) }.map { it.first }
        val byDigits = entries.map { it.first }.filter { ContactStore.digits(it.number).contains(q) }
        return (byName + byDigits).distinctBy { ContactStore.digits(it.number) }.take(limit)
    }

    private fun t9(word: String): String {
        val plain = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD).filter { it.code < 0x300 || it.code > 0x36f }
        return plain.map { ch ->
            when (ch) {
                in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'; in 'j'..'l' -> '5'
                in 'm'..'o' -> '6'; in 'p'..'s' -> '7'; in 't'..'v' -> '8'; in 'w'..'z' -> '9'
                in '0'..'9' -> ch
                else -> '_'
            }
        }.joinToString("")
    }
}

private fun toneFor(key: String): Int? = when (key) {
    "0", "+" -> ToneGenerator.TONE_DTMF_0
    "1" -> ToneGenerator.TONE_DTMF_1
    "2" -> ToneGenerator.TONE_DTMF_2
    "3" -> ToneGenerator.TONE_DTMF_3
    "4" -> ToneGenerator.TONE_DTMF_4
    "5" -> ToneGenerator.TONE_DTMF_5
    "6" -> ToneGenerator.TONE_DTMF_6
    "7" -> ToneGenerator.TONE_DTMF_7
    "8" -> ToneGenerator.TONE_DTMF_8
    "9" -> ToneGenerator.TONE_DTMF_9
    "*" -> ToneGenerator.TONE_DTMF_S
    "#" -> ToneGenerator.TONE_DTMF_P
    else -> null
}

private fun formatNumber(number: String): String =
    runCatching { PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) }.getOrNull() ?: number

private fun duration(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)

private fun typeLabel(t: CallHistory.Type): String = when (t) {
    CallHistory.Type.INCOMING -> "Incoming"
    CallHistory.Type.OUTGOING -> "Outgoing"
    CallHistory.Type.MISSED -> "Missed"
    CallHistory.Type.DECLINED -> "Declined"
    CallHistory.Type.WHATSAPP -> "WhatsApp"
}

private fun typeIcon(t: CallHistory.Type): ImageVector = when (t) {
    CallHistory.Type.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
    CallHistory.Type.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
    CallHistory.Type.MISSED -> Icons.AutoMirrored.Filled.CallMissed
    CallHistory.Type.DECLINED -> Icons.Default.PhoneDisabled
    CallHistory.Type.WHATSAPP -> Icons.AutoMirrored.Filled.Chat
}

private fun dayLabel(at: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(day, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (same(day, today)) return "Yesterday"
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
}
