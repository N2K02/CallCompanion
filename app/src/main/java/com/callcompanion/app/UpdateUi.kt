package com.callcompanion.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Hides the footer while scrolling down and shows it again on scrolling up.
 * Add `Modifier.nestedScroll(FooterScroll.connection)` to the screen's Scaffold.
 */
object FooterScroll {
    var visible by mutableStateOf(true)
        private set

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -6f) visible = false // content moves up = scrolling down
            else if (available.y > 6f) visible = true
            return Offset.Zero
        }
    }
}

/**
 * Shows when an update is available, downloading or ready to install; hidden otherwise.
 * Put it near the top of the main screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    var diagnosticsVisible by remember { mutableStateOf(false) }
    val activity = LocalContext.current.activity()
    val visible = state is AppUpdater.State.Checking || state is AppUpdater.State.Available || state is AppUpdater.State.Downloading ||
        state is AppUpdater.State.NeedsPermission || state is AppUpdater.State.Ready ||
        state is AppUpdater.State.Failed
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state is AppUpdater.State.Failed) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = state) {
                    is AppUpdater.State.Checking -> {
                        Title("Checking for updates…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Contacting GitHub for the latest release.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is AppUpdater.State.Available -> {
                        Title("Update available: ${s.release.versionName}")
                        Notes(s.release)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { diagnosticsVisible = false; AppUpdater.update(s.release) }) { Text("Update now") }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") }
                            TextButton(onClick = { AppUpdater.skip(s.release) }) { Text("Skip this version") }
                        }
                    }
                    is AppUpdater.State.Downloading -> {
                        Title("Downloading ${s.release.versionName}…")
                        val p = s.progress
                        if (p == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Preparing download…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("Downloaded ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                        TextButton(onClick = { AppUpdater.dismiss() }) { Text("Cancel download") }
                    }
                    is AppUpdater.State.NeedsPermission -> {
                        Title("One more step")
                        Text(
                            "Android needs your OK to let this app install its own updates. " +
                                "Turn on \"Allow from this source\", then come back.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { activity?.let { AppUpdater.openInstallPermission(it) } }) { Text("Open settings") }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") }
                        }
                    }
                    is AppUpdater.State.Ready -> {
                        Title("${s.release.versionName} is ready to install")
                        Text(
                            "Android's installer opens. If Play Protect asks, you can scan the app or choose to install anyway.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { AppUpdater.install(s.release) }) { Text("Install") }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") }
                        }
                    }
                    is AppUpdater.State.Failed -> {
                        Title("Update problem")
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { diagnosticsVisible = !diagnosticsVisible }) {
                            Text(if (diagnosticsVisible) "Hide details" else "Show details")
                        }
                        if (diagnosticsVisible) {
                            Text(
                                s.diagnostics.ifBlank { "No additional diagnostic details were captured." },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = {
                                val clipboard = activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Update diagnostics", s.diagnostics))
                            }) { Text("Copy diagnostics") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.release?.let { r -> Button(onClick = { diagnosticsVisible = false; AppUpdater.update(r) }) { Text("Try again") } }
                            TextButton(onClick = { activity?.let { AppUpdater.openReleasePage(it, s.release) } }) {
                                Text("Open release page")
                            }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Close") }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Notes(release: AppUpdater.Release) {
    if (release.notes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What's new", style = MaterialTheme.typography.labelLarge)
        release.notes.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * Slim footer fixed at the bottom: app name and version on the left, the update action on
 * the right. Slides away while scrolling down (see [FooterScroll]) and stays visible while an
 * update is in progress. Put it in the Scaffold's bottomBar.
 */
@Composable
fun UpdateFooterBar(appName: String, modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    val activity = LocalContext.current.activity()
    val busy = state is AppUpdater.State.Downloading || state is AppUpdater.State.Ready ||
        state is AppUpdater.State.NeedsPermission
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = FooterScroll.visible || busy,
        enter = expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        Surface(
            color = scheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                    .heightIn(min = 40.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                ) {
                    Text(
                        appName.take(1),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimaryContainer,
                    )
                }
                Text(
                    "$appName  ·  v${AppUpdater.installedVersionName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                )
                val small = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                val buttonModifier = Modifier.heightIn(min = 32.dp)
                when (val s = state) {
                    is AppUpdater.State.Checking -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("  Checking…", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    is AppUpdater.State.Available -> Button(
                        onClick = { AppUpdater.update(s.release) }, contentPadding = small, modifier = buttonModifier,
                    ) { Text("Update to ${s.release.versionName}", style = MaterialTheme.typography.labelMedium) }
                    is AppUpdater.State.Downloading -> Text(
                        s.progress?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Downloading…",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    is AppUpdater.State.Ready -> Button(
                        onClick = { AppUpdater.install(s.release) }, contentPadding = small, modifier = buttonModifier,
                    ) { Text("Install ${s.release.versionName}", style = MaterialTheme.typography.labelMedium) }
                    is AppUpdater.State.NeedsPermission -> Button(
                        onClick = { activity?.let { AppUpdater.openInstallPermission(it) } },
                        contentPadding = small, modifier = buttonModifier,
                    ) { Text("Allow installs", style = MaterialTheme.typography.labelMedium) }
                    is AppUpdater.State.UpToDate -> FilledTonalButton(
                        onClick = { AppUpdater.check(manual = true) }, contentPadding = small, modifier = buttonModifier,
                    ) { Text("Up to date ✓", style = MaterialTheme.typography.labelMedium) }
                    else -> FilledTonalButton(
                        onClick = { AppUpdater.check(manual = true) }, contentPadding = small, modifier = buttonModifier,
                    ) { Text("Check for updates", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}
