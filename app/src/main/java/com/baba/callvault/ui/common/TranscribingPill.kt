/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.transcription.TranscriptionWorker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Observes whether anything is being transcribed, and what.
 *
 * Both work names are watched: the nightly sweep and a recording the user tapped are the same thing
 * to whoever is looking at the phone, and only one of them can be running at a time.
 */
@Composable
fun rememberTranscribingPillState(): State<TranscribingPillState> {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }

    return produceState<TranscribingPillState>(
        initialValue = TranscribingPillState.Hidden,
        workManager
    ) {
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(TranscriptionScheduler.WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(TranscriptionScheduler.MANUAL_WORK_NAME),
            // Every run since C124 carries this tag, including each after-call run (its own unique work).
            workManager.getWorkInfosByTagFlow(TranscriptionScheduler.TAG_RUN)
        ) { sweep, manual, tagged -> (sweep + manual + tagged).firstOrNull { it.state == WorkInfo.State.RUNNING } }
            .collect { running -> value = running.toPillState() }
    }
}

private fun WorkInfo?.toPillState(): TranscribingPillState {
    if (this == null) return TranscribingPillState.Hidden
    return TranscribingPillState.from(
        isRunning = state == WorkInfo.State.RUNNING,
        completed = progress.getInt(TranscriptionWorker.KEY_COMPLETED, 0),
        total = progress.getInt(TranscriptionWorker.KEY_TOTAL, 0),
        current = progress.getString(TranscriptionWorker.KEY_CURRENT),
        percent = progress.getInt(TranscriptionWorker.KEY_PERCENT, 0)
    )
}

/**
 * The pill beside the title while transcription runs (decision A3).
 *
 * Nothing at all when idle — this is the state the phone is in almost always, and the feature was
 * asked for silent. Renders nothing for [TranscribingPillState.Hidden] so the caller can hand it the
 * state unconditionally.
 */
@Composable
fun TranscribingPill(state: TranscribingPillState, onClick: () -> Unit) {
    if (state == TranscribingPillState.Hidden) return

    val accent = MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, shape = CircleShape, color = accent.copy(alpha = 0.12f)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // trackColor stated for the same reason as on the row: left to the theme it resolves to
            // CoralDeep and puts a red ring inside a teal pill.
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = accent,
                trackColor = accent.copy(alpha = 0.20f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = state.label(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = accent
            )
        }
    }
}

/** "3/12" for a backlog; the word alone for a single recording or before the count is known. */
@Composable
private fun TranscribingPillState.label(): String = when (this) {
    TranscribingPillState.Hidden,
    TranscribingPillState.Starting -> stringResource(R.string.transcribing_pill_working)
    // The percentage replaces the word rather than joining it. "Transcribing" said nothing that the
    // spinner beside it was not already saying, and a run over a long call sat there for minutes
    // looking identical to a hang.
    is TranscribingPillState.Single ->
        if (percent > 0) stringResource(R.string.transcribing_pill_percent, percent)
        else stringResource(R.string.transcribing_pill_working)
    is TranscribingPillState.Batch ->
        if (percent > 0) stringResource(R.string.transcribing_pill_progress_percent, position, total, percent)
        else stringResource(R.string.transcribing_pill_progress, position, total)
}

/**
 * What the pill opens: which recording is being transcribed, and the way to stop.
 *
 * @param recordings used to name the recording the way every other screen names it. The progress
 *   carries a file name, and showing that raw would read as debug output next to the contact names
 *   in the list behind it.
 * @param onStopped called after the run has been cancelled, so the caller can close this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribingSheet(
    state: TranscribingPillState,
    recordings: List<RecordingItem>,
    onDismiss: () -> Unit,
    onStopped: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val displayName = when (state) {
        is TranscribingPillState.Single -> state.current
        is TranscribingPillState.Batch -> state.current
        else -> null
    }
    val current = displayName?.let { RecordingLabel.forDisplayName(recordings, it) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.transcribing_sheet_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = current ?: stringResource(R.string.transcribing_sheet_starting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state is TranscribingPillState.Batch) {
                Text(
                    text = stringResource(
                        R.string.transcribing_sheet_remaining,
                        (state.total - state.position).coerceAtLeast(0)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            val scope = rememberCoroutineScope()
            TextButton(onClick = {
                // Stops the run without giving up the nightly schedule, and releases the row it was
                // working on — see stopNow. Off the main thread: it touches the database.
                scope.launch { TranscriptionScheduler.stopNow(context) }
                onStopped()
            }) {
                Text(stringResource(R.string.transcribing_sheet_stop))
            }
        }
    }
}
