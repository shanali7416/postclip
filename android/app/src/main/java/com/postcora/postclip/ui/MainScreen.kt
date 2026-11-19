package com.postcora.postclip.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.postcora.postclip.MainViewModel
import com.postcora.postclip.core.ClipValidation
import com.postcora.postclip.core.SizeEstimate
import com.postcora.postclip.core.TimeFormat
import com.postcora.postclip.download.ClipSaver
import com.postcora.postclip.download.DownloadState

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onDownload: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PostClip", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("by PostCora", style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { showAbout = true }) { Icon(Icons.Outlined.Info, contentDescription = "About") }
            }

            OutlinedTextField(
                value = ui.link, onValueChange = vm::setLink, modifier = Modifier.fillMaxWidth(),
                label = { Text("YouTube link") }, singleLine = true,
                placeholder = { Text("https://youtu.be/…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.fetch() }),
            )
            Button(onClick = vm::fetch, enabled = !ui.loading && ui.link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                if (ui.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Get video")
            }
            ui.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val meta = ui.meta
            if (meta != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(model = meta.thumbnailUrl, contentDescription = null,
                        modifier = Modifier.width(140.dp).height(79.dp).clip(RoundedCornerShape(8.dp)))
                    Column {
                        Text(meta.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        meta.uploader?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("Length: ${TimeFormat.format(meta.durationSeconds)}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text("Clip range", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(ui.startText, vm::setStart, Modifier.weight(1f), label = { Text("Start (h:mm:ss)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(ui.endText, vm::setEnd, Modifier.weight(1f), label = { Text("End (h:mm:ss)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("Hours:minutes:seconds — m:ss also works (0:30 = 30 seconds).", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClipValidation.PRESETS.forEach { p ->
                        OutlinedButton(onClick = { vm.applyPreset(p) }) { Text("${p}s") }
                    }
                }
                ui.rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                QualityPicker(ui.heights, ui.selectedHeight, vm::setHeight)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ui.crop916, vm::setCrop)
                    Text("9:16 vertical center crop (Shorts / Reels / TikTok)")
                }

                if (ui.rangeError == null && ui.estimateBytes > 0) {
                    Text("Estimated clip download: ≈${SizeEstimate.formatBytes(ui.estimateBytes)} — only this range is fetched",
                        style = MaterialTheme.typography.bodySmall)
                }

                DownloadArea(dl, ui.rangeError == null && !ui.preparing, onDownload, vm::cancel,
                    onOpen = { uri -> launch(context, ClipSaver.openIntent(uri)) },
                    onShare = { uri -> launch(context, ClipSaver.shareIntent(uri)) })
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Use PostClip only for your own videos or content you are authorized to reuse. " +
                    "Ended videos only — live streams, private, DRM or login-walled videos are not supported.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false }, onLink = {
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://postcora.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    })
}

private fun launch(context: android.content.Context, intent: Intent) {
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to handle this", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun QualityPicker(heights: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Quality", Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }, enabled = heights.isNotEmpty()) { Text(if (heights.isEmpty()) "—" else "${selected}p") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                heights.forEach { h ->
                    DropdownMenuItem(text = { Text("${h}p" + if (h == 720) " (default)" else "") }, onClick = { onSelect(h); open = false })
                }
            }
        }
    }
}

@Composable
private fun DownloadArea(
    dl: DownloadState, canStart: Boolean, onDownload: () -> Unit, onCancel: () -> Unit,
    onOpen: (Uri) -> Unit, onShare: (Uri) -> Unit,
) {
    when (dl) {
        is DownloadState.Running -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${dl.stage} — ${dl.percent}%", fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { dl.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${SizeEstimate.formatBytes(dl.bytesDownloaded)} / ≈${SizeEstimate.formatBytes(dl.bytesTotalEstimate)}")
                Text(buildString {
                    append(if (dl.speedBps > 0) SizeEstimate.formatSpeed(dl.speedBps) else "— KB/s")
                    append(" · ETA ")
                    append(dl.etaSeconds?.let { TimeFormat.format(it) } ?: "—")
                })
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        is DownloadState.Done -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Clip saved", fontWeight = FontWeight.SemiBold)
                Text("${dl.displayPath} · ${SizeEstimate.formatBytes(dl.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpen(dl.uri) }) { Text("Open") }
                    OutlinedButton(onClick = { onShare(dl.uri) }) { Text("Share") }
                    TextButton(onClick = onDownload, enabled = canStart) { Text("Download again") }
                }
            }
        }
        is DownloadState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(dl.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, enabled = canStart, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
        else -> {
            if (dl is DownloadState.Cancelled) Text("Download cancelled.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onDownload, enabled = canStart, modifier = Modifier.fillMaxWidth()) { Text("Download clip") }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit, onLink: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onLink) { Text("Open postcora.com") } },
        title = { Text("PostClip by PostCora") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cut a short clip from a YouTube video. Only the range you choose is downloaded.")
                Text("Created by PostCora.com — https://postcora.com")
                Text("Use only for your own content or content you are authorized to reuse.", style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
