/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.SpeakerNames
import com.baba.callvault.data.clips.ClipCutter
import com.baba.callvault.data.clips.ClipFiles
import com.baba.callvault.data.clips.ClipLine
import com.baba.callvault.data.clips.ClipNaming
import com.baba.callvault.data.clips.ClipRange
import com.baba.callvault.data.clips.ClipTranscriber
import com.baba.callvault.data.clips.ClipTranscript
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.transcripts.TranscriptRepository
import com.baba.callvault.data.transcripts.TranscriptStatus
import com.baba.callvault.system.shareClip
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.RecordingLabel
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.viewmodels.RecordingPlaybackController
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How an untranscribed call gets the text that goes with its clip. */
private enum class TextSource { PART, WHOLE_CALL }

/** What the Share button is doing. */
private sealed interface ClipWork {
    data object Idle : ClipWork
    data object Cutting : ClipWork
    data object TranscribingPart : ClipWork
    data object WaitingForCall : ClipWork

    /** Stopped on a problem; the clip itself is ready and can still go without text. */
    data class TextProblem(val message: Int) : ClipWork
    data object CutFailed : ClipWork
}

/**
 * "Share a part": choose a part of a recording on its waveform, listen to it, and share it as a new file — with the
 * transcript of just that part if the user wants it. Option A of the 2026-11-04 design; a clip is never saved as a
 * recording.
 *
 * @param initialPositionMs where the user was listening, to open with [ClipRange.FROM_POSITION_MS] from there; null to
 *   open with the whole call selected (from the list).
 * @param onTranscribeWholeCall the app's normal "transcribe this call", with its own confirmations.
 */
@Composable
internal fun ShareClipScreen(
    item: RecordingItem,
    initialPositionMs: Long?,
    playback: RecordingPlaybackController.PlaybackState,
    peaks: FloatArray,
    flags: List<Long>,
    transcriptStatus: TranscriptStatus,
    onPlayFrom: (Long) -> Unit,
    onPause: () -> Unit,
    onSyncPosition: () -> Unit,
    onTranscribeWholeCall: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val labels = rememberLibrarySpeakerLabels()

    val durationMs by produceState(item.durationSeconds?.times(1000) ?: 0L, item.uri) {
        val read = withContext(Dispatchers.IO) { runCatching { AudioDecoder.durationMs(context, item.uri) }.getOrDefault(0L) }
        if (read > 0) value = read
    }
    // Null while it is being read; "" when the recording cannot be read at all.
    val mime by produceState<String?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { ClipCutter.mimeOf(context, item.uri) }.orEmpty()
    }

    var range by remember(durationMs) {
        mutableStateOf(
            initialPositionMs?.let { ClipRange.fromPosition(it, durationMs) } ?: ClipRange.whole(durationMs),
        )
    }

    // The call's own transcript, with who-is-who resolved the way exports do it.
    val transcript by remember(item.displayName) {
        TranscriptRepository.transcript(context, item.displayName)
    }.collectAsState(initial = TranscriptRepository.TranscriptRead.Unread)
    val callLines by produceState(emptyList<ClipLine>(), transcript) {
        val read = transcript as? TranscriptRepository.TranscriptRead.Read
        val segments = read?.transcript?.segments.orEmpty()
        val names = SpeakerNames(
            map = SpeakerTurnsRepository.trustedMap(context),
            you = labels.you,
            contact = RecordingLabel.of(item) ?: item.displayName,
            sideA = labels.sideA,
            sideB = labels.sideB,
        )
        value = segments.map { ClipLine(it.startMs, it.endMs, it.text, names.of(it.speaker)) }
    }

    val transcribed = transcriptStatus == TranscriptStatus.DONE
    val callInProgress = transcriptStatus == TranscriptStatus.QUEUED || transcriptStatus == TranscriptStatus.RUNNING
    var includeText by remember(transcribed) { mutableStateOf(transcribed) }
    var source by remember { mutableStateOf(TextSource.PART) }
    var work by remember { mutableStateOf<ClipWork>(ClipWork.Idle) }
    var readyClip by remember { mutableStateOf<ClipCutter.Result?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val statusNow by rememberUpdatedState(transcriptStatus)
    val linesNow by rememberUpdatedState(callLines)

    val isThis = playback.activeUri == item.uri
    val playing = isThis && playback.phase == RecordingPlaybackController.Phase.PLAYING
    var previewing by remember { mutableStateOf(false) }
    val rangeNow by rememberUpdatedState(range)

    // Preview: play the part and stop at its end. Polled faster than the app's 500 ms tick so it stops on time.
    LaunchedEffect(previewing) {
        while (previewing) {
            onSyncPosition()
            delay(PREVIEW_POLL_MS)
            if (playback.activeUri == item.uri && playback.positionMs >= rangeNow.endMs) {
                onPause()
                previewing = false
            }
        }
    }
    LaunchedEffect(playing) { if (!playing) previewing = false }

    fun share(clip: ClipCutter.Result, text: String?) {
        context.shareClip(clip.file, ClipCutter.shareMimeFor(clip.mime), text?.takeIf { it.isNotBlank() })
        work = ClipWork.Idle
    }

    fun start() {
        val clipMime = mime ?: return
        job = scope.launch {
            work = ClipWork.Cutting
            val name = ClipNaming.fileName(item.displayName, range, ClipCutter.extensionFor(clipMime))
            val clip = withContext(Dispatchers.IO) {
                runCatching { ClipCutter.cut(context, item.uri, range, ClipFiles.newFile(context, name)) }
                    .onFailure { AppLogger.w(TAG, "Clip failed: ${it.message}") }
                    .getOrNull()
            }
            if (clip == null) {
                work = ClipWork.CutFailed
                return@launch
            }
            readyClip = clip
            when {
                !includeText -> share(clip, null)
                transcribed -> share(clip, ClipTranscript.render(ClipTranscript.linesIn(callLines, range)))
                source == TextSource.PART && !callInProgress -> {
                    work = ClipWork.TranscribingPart
                    when (val outcome = ClipTranscriber.transcribe(context, clip, range)) {
                        is ClipTranscriber.Outcome.Done -> share(clip, ClipTranscript.render(outcome.lines))
                        ClipTranscriber.Outcome.Busy -> work = ClipWork.TextProblem(R.string.share_clip_busy)
                        ClipTranscriber.Outcome.NotSetUp -> work = ClipWork.TextProblem(R.string.share_clip_not_set_up)
                        is ClipTranscriber.Outcome.Failed -> work = ClipWork.TextProblem(R.string.share_clip_transcribe_failed)
                    }
                }
                else -> {
                    // The whole call: through the app's own transcription, then only this part's lines.
                    if (!callInProgress) onTranscribeWholeCall()
                    work = ClipWork.WaitingForCall
                    val ended = snapshotFlow { statusNow }
                        .first { it == TranscriptStatus.DONE || it == TranscriptStatus.FAILED }
                    if (ended == TranscriptStatus.DONE) {
                        val lines = snapshotFlow { linesNow }.first { it.isNotEmpty() }
                        share(clip, ClipTranscript.render(ClipTranscript.linesIn(lines, range)))
                    } else {
                        work = ClipWork.TextProblem(R.string.share_clip_transcribe_failed)
                    }
                }
            }
        }
    }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.share_clip_title),
        subtitle = RecordingLabel.of(item) ?: item.displayName,
        onBack = onBack,
        bottomBar = {
            ShareBar(
                lengthMs = range.lengthMs,
                work = work,
                enabled = !mime.isNullOrEmpty() && ClipCutter.canClip(mime) && durationMs > 0,
                onShare = { start() },
                onShareWithoutText = {
                    // Stops a transcription still running, so the clip is not shared a second time when it ends.
                    job?.cancel()
                    readyClip?.let { share(it, null) }
                },
                onCancel = {
                    job?.cancel()
                    work = ClipWork.Idle
                },
                progress = { ClipTranscriber.progressPercent() },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (mime != null && (mime.isNullOrEmpty() || !ClipCutter.canClip(mime))) {
                CvCard { Text(stringResource(R.string.share_clip_unsupported), style = MaterialTheme.typography.bodyMedium) }
                return@Column
            }
            CvCard {
                ClipRangeWaveform(
                    peaks = peaks,
                    durationMs = durationMs,
                    range = range,
                    flags = flags,
                    playheadMs = if (isThis) playback.positionMs.toLong() else null,
                    onRangeChange = { range = it },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeLabel(R.string.share_clip_start, range.startMs)
                    TimeLabel(R.string.share_clip_length, range.lengthMs)
                    TimeLabel(R.string.share_clip_end, range.endMs)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { range = range.withStart(playback.positionMs.toLong(), durationMs) },
                    enabled = isThis,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.share_clip_start_here), maxLines = 1) }
                FilledIconButton(
                    onClick = {
                        if (playing) {
                            onPause()
                        } else {
                            onPlayFrom(range.startMs)
                            previewing = true
                        }
                    },
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.share_clip_pause else R.string.share_clip_preview),
                    )
                }
                OutlinedButton(
                    onClick = { range = range.withEnd(playback.positionMs.toLong(), durationMs) },
                    enabled = isThis,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.share_clip_end_here), maxLines = 1) }
            }
            Text(
                stringResource(R.string.share_clip_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TranscriptChoice(
                include = includeText,
                onInclude = { includeText = it },
                transcribed = transcribed,
                callInProgress = callInProgress,
                linesInPart = if (transcribed) ClipTranscript.linesIn(callLines, range).size else -1,
                source = source,
                onSource = { source = it },
            )
        }
    }
}

@Composable
private fun TimeLabel(label: Int, ms: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ClipRange.clock(ms), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun TranscriptChoice(
    include: Boolean,
    onInclude: (Boolean) -> Unit,
    transcribed: Boolean,
    callInProgress: Boolean,
    /** Lines of the call's transcript inside the part, or -1 when the call has none. */
    linesInPart: Int,
    source: TextSource,
    onSource: (TextSource) -> Unit,
) {
    CvCard {
        Row(
            Modifier.fillMaxWidth().toggleable(value = include, role = Role.Switch, onValueChange = onInclude),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.share_clip_include_text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = include, onCheckedChange = null)
        }
        if (!include) return@CvCard
        val note = when {
            transcribed && linesInPart == 0 -> R.string.share_clip_text_none_in_part
            transcribed -> R.string.share_clip_text_ready
            callInProgress -> R.string.share_clip_text_call_running
            else -> null
        }
        if (note != null) {
            Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@CvCard
        }
        Column(Modifier.selectableGroup()) {
            SourceOption(
                selected = source == TextSource.PART,
                title = R.string.share_clip_transcribe_part,
                detail = R.string.share_clip_transcribe_part_detail,
                onClick = { onSource(TextSource.PART) },
            )
            SourceOption(
                selected = source == TextSource.WHOLE_CALL,
                title = R.string.share_clip_transcribe_whole,
                detail = R.string.share_clip_transcribe_whole_detail,
                onClick = { onSource(TextSource.WHOLE_CALL) },
            )
        }
    }
}

@Composable
private fun SourceOption(selected: Boolean, title: Int, detail: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShareBar(
    lengthMs: Long,
    work: ClipWork,
    enabled: Boolean,
    onShare: () -> Unit,
    onShareWithoutText: () -> Unit,
    onCancel: () -> Unit,
    progress: () -> Int,
) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (work) {
            ClipWork.Idle, ClipWork.CutFailed -> {
                if (work == ClipWork.CutFailed) {
                    Text(stringResource(R.string.share_clip_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onShare, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_clip_share, ClipRange.clock(lengthMs)))
                }
            }
            ClipWork.Cutting, ClipWork.TranscribingPart, ClipWork.WaitingForCall -> {
                // Leaving the screen also stops the work: the scope is the screen's.
                var percent by remember { mutableStateOf(0) }
                LaunchedEffect(work) {
                    while (work == ClipWork.TranscribingPart) {
                        percent = progress()
                        delay(PREVIEW_POLL_MS * 5)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (work) {
                            ClipWork.Cutting -> stringResource(R.string.share_clip_working_cut)
                            ClipWork.TranscribingPart -> stringResource(R.string.share_clip_working_part, percent)
                            else -> stringResource(R.string.share_clip_working_call)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (work != ClipWork.Cutting) {
                    TextButton(onClick = onShareWithoutText, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.share_clip_share_without_text))
                    }
                }
            }
            is ClipWork.TextProblem -> {
                Text(stringResource(work.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_clip_back))
                    }
                    Button(onClick = onShareWithoutText, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_clip_share_without_text))
                    }
                }
            }
        }
    }
}

private const val TAG = "CV:ShareClip"
private const val PREVIEW_POLL_MS = 100L
