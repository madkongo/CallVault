/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.ui.viewmodels.HomeViewModel
import com.baba.callvault.ui.viewmodels.RecordingPlaybackController
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The main Home screen shown once onboarding and the setup wizard are complete.
 *
 * Renders two sections:
 *  - a STATUS CARD reflecting the app's best-effort health (ADB / daemon / folder), and
 *  - the in-app RECORDINGS list with an inline [MediaPlayer]-backed player for the active row.
 *
 * The Settings affordance is preserved as a top-app-bar action.
 *
 * @param onOpenSettings Called when the user taps the Settings action; the router maps this to
 *                       manual navigation to [com.baba.callvault.ui.navigation.AppScreen.Settings].
 * @param modifier       Optional layout modifier.
 * @param viewModel      The Home "Brain"; defaults to a [viewModel]-scoped [HomeViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val playback by viewModel.playback.collectAsState()

    // Refresh status + recordings whenever the user returns to the screen (e.g. after a new call).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While a track is playing, tick the player position so the slider tracks playback.
    LaunchedEffect(playback.phase) {
        while (playback.phase == RecordingPlaybackController.Phase.PLAYING) {
            viewModel.syncPlaybackPosition()
            delay(500)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.home_open_settings)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { StatusCard(status = uiState.status) }

            item {
                Text(
                    text = stringResource(R.string.home_recordings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }

            if (uiState.recordings.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.home_recordings_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(uiState.recordings, key = { it.uri.toString() }) { item ->
                    RecordingRow(
                        item = item,
                        isActive = playback.activeUri == item.uri,
                        playback = playback,
                        onPlay = { viewModel.play(item) },
                        onPause = { viewModel.pausePlayback() },
                        onResume = { viewModel.resumePlayback() },
                        onSeek = { viewModel.seekTo(it) },
                        onDelete = { viewModel.deleteRecording(item) }
                    )
                }
            }
        }
    }
}

/**
 * The top status card. Non-ready states use the error container color and a warning icon to draw
 * attention; the ready state uses a subtle primary tint with a check icon.
 */
@Composable
private fun StatusCard(status: HomeViewModel.HomeStatus) {
    val containerColor =
        if (status.isReady) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    val contentColor =
        if (status.isReady) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onErrorContainer
    val icon: ImageVector = if (status.isReady) Icons.Default.CheckCircle else Icons.Default.Warning

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(status.titleResId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(status.suggestionResId),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * A single recording row: direction icon, name/date/number, and size. When [isActive], an inline
 * player (play/pause + seek slider + elapsed/total) is shown beneath the metadata.
 */
@Composable
private fun RecordingRow(
    item: RecordingItem,
    isActive: Boolean,
    playback: RecordingPlaybackController.PlaybackState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Prefer the contact name, then the parsed number, then the raw file name.
    val primaryLabel = item.contactName ?: item.number ?: item.displayName

    if (showDeleteDialog) {
        DeleteRecordingDialog(
            name = primaryLabel,
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!isActive) onPlay() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectionIcon(item.direction)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = primaryLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = item.displayDate ?: item.displayName
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatSize(item.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.home_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                InlinePlayer(
                    playback = playback,
                    onPlay = onPlay,
                    onPause = onPause,
                    onResume = onResume,
                    onSeek = onSeek
                )
            }
        }
    }
}

/** Confirmation dialog shown before permanently deleting a recording. */
@Composable
private fun DeleteRecordingDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Default.Delete, contentDescription = null) },
        title = { Text(text = stringResource(R.string.home_delete_confirm_title)) },
        text = { Text(text = stringResource(R.string.home_delete_confirm_message, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.home_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.general_cancel))
            }
        }
    )
}

/** The inline player controls (loading / error / play-pause + seek slider + elapsed/total). */
@Composable
private fun InlinePlayer(
    playback: RecordingPlaybackController.PlaybackState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit
) {
    when (playback.phase) {
        RecordingPlaybackController.Phase.LOADING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_player_loading),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        RecordingPlaybackController.Phase.ERROR -> {
            Text(
                text = stringResource(R.string.home_player_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        else -> {
            val isPlaying = playback.phase == RecordingPlaybackController.Phase.PLAYING
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (isPlaying) onPause() else onResume() }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.home_player_pause else R.string.home_player_play
                        )
                    )
                }
                Text(
                    text = formatMillis(playback.positionMs),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = playback.positionMs.toFloat(),
                    onValueChange = { onSeek(it.toInt()) },
                    valueRange = 0f..(playback.durationMs.takeIf { it > 0 } ?: 1).toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = formatMillis(playback.durationMs),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun DirectionIcon(direction: RecordingDirection?) {
    val icon = when (direction) {
        RecordingDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
        RecordingDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
        null -> Icons.Default.PlayArrow
    }
    val description = when (direction) {
        RecordingDirection.INCOMING -> stringResource(R.string.general_incoming)
        RecordingDirection.OUTGOING -> stringResource(R.string.general_outgoing)
        null -> null
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp)
    )
}

// -------- Formatting helpers

/** Formats a byte count as a compact human-readable size (e.g. "1.2 MB"). */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}

/** Formats a millisecond duration as m:ss. */
private fun formatMillis(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
