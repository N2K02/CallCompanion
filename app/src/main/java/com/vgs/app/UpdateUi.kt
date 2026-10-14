package com.vgs.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Shows when an update is available, downloading or installing; hidden otherwise.
 * Put it near the top of the main screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    val activity = LocalContext.current.activity()
    val visible = state is AppUpdater.State.Available || state is AppUpdater.State.Downloading ||
        state is AppUpdater.State.NeedsPermission || state is AppUpdater.State.Installing ||
        state is AppUpdater.State.Confirm || (state is AppUpdater.State.Failed)
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
                    is AppUpdater.State.Available -> {
                        Title("Update available: ${s.release.versionName}")
                        Notes(s.release)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                            Button(onClick = { AppUpdater.update(s.release) }) { Text("Update now") }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") }
                            TextButton(onClick = { AppUpdater.skip(s.release) }) { Text("Skip this version") }
                        }
                    }
                    is AppUpdater.State.Downloading -> {
                        Title("Downloading ${s.release.versionName}…")
                        val p = s.progress
                        if (p == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { AppUpdater.dismiss() }) { Text("Cancel") }
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
                    is AppUpdater.State.Installing -> {
                        Title("Installing ${s.release.versionName}…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("The app will close and reopen on the new version.", style = MaterialTheme.typography.bodySmall)
                    }
                    is AppUpdater.State.Confirm -> {
                        Title("Ready to install ${s.release.versionName}")
                        Text("Confirm in Android's install window.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { activity?.let { AppUpdater.confirm(it, s) } }) { Text("Install") }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") }
                        }
                    }
                    is AppUpdater.State.Failed -> {
                        Title("Update problem")
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.release?.let { r -> Button(onClick = { AppUpdater.update(r) }) { Text("Try again") } }
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

/** "MRadio 1.7 · Check for updates": put it at the bottom of the main screen. */
@Composable
fun UpdateFooter(appName: String, modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            "$appName ${AppUpdater.installedVersionName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            is AppUpdater.State.Checking -> Text(
                "  ·  Checking…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is AppUpdater.State.UpToDate -> TextButton(onClick = { AppUpdater.check(manual = true) }) {
                Text("Up to date ✓", style = MaterialTheme.typography.bodySmall)
            }
            else -> TextButton(onClick = { AppUpdater.check(manual = true) }) {
                Text("Check for updates", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
