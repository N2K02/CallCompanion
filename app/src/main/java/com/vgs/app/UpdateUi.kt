package com.vgs.app

import android.app.Activity
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
import androidx.compose.foundation.layout.height
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
 * Always shown: app version and a "Check for updates" button; turns into the update /
 * download / install card when there is something to do. Put it at the top of each page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateCard(modifier: Modifier = Modifier, appName: String = "VGS") {
    val state by AppUpdater.state.collectAsState()
    val activity = LocalContext.current.activity()
    val quiet = state is AppUpdater.State.Idle || state is AppUpdater.State.Checking ||
        state is AppUpdater.State.UpToDate
    AnimatedVisibility(visible = true, modifier = modifier) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    state is AppUpdater.State.Failed -> MaterialTheme.colorScheme.errorContainer
                    quiet -> MaterialTheme.colorScheme.surfaceContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = state) {
                    is AppUpdater.State.Available -> {
                        Title("Update available: ${s.release.versionName}")
                        Text(
                            "Installed: v${AppUpdater.installedVersionName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Notes(s.release)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.release?.let { r -> Button(onClick = { AppUpdater.update(r) }) { Text("Try again") } }
                            TextButton(onClick = { activity?.let { AppUpdater.openReleasePage(it, s.release) } }) {
                                Text("Open release page")
                            }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Close") }
                        }
                    }
                    else -> { // Idle, Checking, UpToDate
                        Title("$appName  ·  v${AppUpdater.installedVersionName}")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { AppUpdater.check(manual = true) },
                                enabled = state !is AppUpdater.State.Checking,
                                modifier = Modifier.height(48.dp),
                            ) { Text("Check for updates") }
                            when (state) {
                                is AppUpdater.State.Checking -> {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                                }
                                is AppUpdater.State.UpToDate ->
                                    Text("Up to date ✓", style = MaterialTheme.typography.bodyMedium)
                                else -> Unit
                            }
                        }
                    }
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
