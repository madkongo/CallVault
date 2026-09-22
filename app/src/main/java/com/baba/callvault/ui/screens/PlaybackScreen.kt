/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import com.baba.callvault.ui.common.CallOriginBadge
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.TranscriptStatus
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import com.baba.callvault.data.transcripts.TagName
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.rememberNoteDraft
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.RecordingLabel
import com.baba.callvault.ui.common.SummaryCard
import com.baba.callvault.ui.common.SummaryCardState
import com.baba.callvault.ui.common.TranscriptActionButton
import com.baba.callvault.ui.common.WaveformBar
import com.baba.callvault.ui.common.TranscriptTimestamp
import com.baba.callvault.ui.viewmodels.PlaybackControls
import com.baba.callvault.ui.viewmodels.RecordingPlaybackController
import java.util.Locale

/**
 * One recording, on its own screen: who it was with, and the controls to listen to it properly.
 *
 * Exists because the inline player could only ever be a strip — it shares a row with the list, so it
 * could hold a play button and a slider and nothing else. Reviewing a ninety-minute call needs more
 * than that: somewhere to skip back over a sentence you missed, and a speed control, which is the
 * single thing that makes a long call reviewable at all.
 *
 * The transcript is a doorway rather than a panel here. It already has a sheet that does timestamps,
 * right-to-left text and seek-on-tap; duplicating any of that would mean two things to keep correct.
 */
@Composable
fun PlaybackScreen(
    item: RecordingItem,
    playback: RecordingPlaybackController.PlaybackState,
    transcriptStatus: TranscriptStatus,
    /** How far into this recording's transcription, 0-100. Zero means not known, and spins. */
    transcriptPercent: Int,
    summaryState: SummaryCardState,
    peaks: FloatArray,
    note: String,
    onNoteChange: (String) -> Unit,
    /** The user's own labels for this call — see [com.baba.callvault.data.transcripts.TagRepository]. */
    tags: List<String> = emptyList(),
    /** Every tag already in use anywhere, offered as suggestions so near-duplicates do not accrue. */
    knownTags: List<String> = emptyList(),
    /** Moments marked during the call, as offsets into the audio, earliest first. */
    flags: List<Long> = emptyList(),
    /**
     * Play from a mark. NOT a seek: [onSeek] only moves a track that is already prepared, so on a
     * recording that has not been played yet it clamps to a duration of zero and silently starts
     * from the beginning. Same reason the transcript lines and summary timestamps use their own
     * play-from rather than seeking.
     */
    onPlayFromFlag: (Long) -> Unit = {},
    onRemoveFlag: (Long) -> Unit = {},
    /** Whether this recording is starred — see [com.baba.callvault.data.transcripts.FavouriteRepository]. */
    isFavourite: Boolean = false,
    onToggleFavourite: () -> Unit = {},
    onAddTag: (String) -> Unit = {},
    onRemoveTag: (String) -> Unit = {},
    /** Renames a tag on every recording carrying it — the only way to fix a typo already applied. */
    onRenameTag: (String, String) -> Unit = { _, _ -> },
    onDeleteTagEverywhere: (String) -> Unit = {},
    onSummarise: () -> Unit,
    onStopSummary: () -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenTranscript: () -> Unit,
    onTranscribe: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = playback.activeUri == item.uri
    val isPlaying = isActive && playback.phase == RecordingPlaybackController.Phase.PLAYING
    val isLoading = isActive && playback.phase == RecordingPlaybackController.Phase.LOADING

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.playback_title),
        onBack = onBack
    ) { innerPadding ->
        // Scrollable, and not optionally so: the cards below the player are laid out past the
        // bottom of a 6.7" screen, so without this the Delete row existed but could never be
        // reached or tapped. The bottom padding keeps that last card clear of the gesture bar
        // rather than flush against it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CallHeaderCard(
                item = item,
                transcriptStatus = transcriptStatus,
                transcriptPercent = transcriptPercent,
                onOpenTranscript = onOpenTranscript,
                onTranscribe = onTranscribe,
                isFavourite = isFavourite,
                onToggleFavourite = onToggleFavourite,
                onDelete = onDelete
            )
            PlayerCard(
                peaks = peaks,
                knownDurationMs = ((item.durationSeconds ?: 0L) * 1000L).toInt(),
                playback = playback,
                isActive = isActive,
                isPlaying = isPlaying,
                isLoading = isLoading,
                onPlay = onPlay,
                onPause = onPause,
                onResume = onResume,
                onSeek = onSeek,
                onSkip = onSkip,
                onCycleSpeed = onCycleSpeed
            )
            // Directly under the player, because every one of these is an instruction to the player:
            // tap and it seeks there. Further down they would be a list of numbers to read rather
            // than controls to use.
            if (flags.isNotEmpty()) {
                MarkedMomentsCard(
                    flags = flags,
                    onPlayFrom = onPlayFromFlag,
                    onRemove = onRemoveFlag
                )
            }
            // Above the transcript row and the note, because it answers the question the transcript
            // answers more slowly — and because a stamp in it is a jump into the player above.
            SummaryCard(
                state = summaryState,
                onCreate = onSummarise,
                onRedo = onSummarise,
                onStop = onStopSummary,
                // Seek, then play. A jump point that silently moved the cursor without starting
                // would look like nothing happened.
                onSeek = { millis ->
                    onSeek(millis.toInt())
                    if (!isPlaying) onPlay()
                }
            )
            // Between the summary and the note: a tag is the shortest thing on the screen and the
            // one most often applied right after listening, so it sits where the thumb already is
            // rather than below a text field that opens the keyboard.
            TagCard(
                tags = tags,
                knownTags = knownTags,
                onAddTag = onAddTag,
                onRemoveTag = onRemoveTag,
                onRenameTag = onRenameTag,
                onDeleteTagEverywhere = onDeleteTagEverywhere
            )
            NoteCard(note = note, recordingKey = item.displayName, onNoteChange = onNoteChange)
        }
    }
}

/**
 * The moments marked during the call, as chips that seek.
 *
 * A tap plays from there; a long press removes the mark. Playing rather than seeking, because a tap
 * on a moment means "let me hear this", and a seek on a recording that has not been played yet does
 * not even move the cursor — it clamps to zero and starts from the top. Long press rather than an X on every
 * chip: the row is read far more often than it is edited, and a delete target beside each entry in a
 * row people tap to navigate is a mis-tap that destroys something.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MarkedMomentsCard(
    flags: List<Long>,
    onPlayFrom: (Long) -> Unit,
    onRemove: (Long) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    CvCard(contentPadding = PaddingValues(16.dp)) {
        Column {
            Text(
                text = stringResource(R.string.playback_marked_moments),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                flags.forEach { atMs ->
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f))
                            .border(1.dp, accent.copy(alpha = 0.5f), CircleShape)
                            .combinedClickable(
                                onClick = { onPlayFrom(atMs) },
                                onLongClick = { onRemove(atMs) }
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatMarkTime(atMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = accent
                        )
                    }
                }
            }
        }
    }
}

/** m:ss, or h:mm:ss once a call is long enough to need it. */
private fun formatMarkTime(atMs: Long): String {
    val totalSeconds = atMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Who the call was with, which way it went, when — and what can be done to it.
 *
 * The two actions live here rather than in cards of their own further down. Both were a full-width
 * row apiece below the player, which put the transcript a scroll away and the delete off the bottom
 * of the screen entirely; as icons beside the name they are visible the moment the screen opens.
 */
@Composable
private fun CallHeaderCard(
    item: RecordingItem,
    transcriptStatus: TranscriptStatus,
    transcriptPercent: Int,
    onOpenTranscript: () -> Unit,
    onTranscribe: () -> Unit,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val label = RecordingLabel.of(item) ?: item.displayName

    CvCard(contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The initial, not a contact photo. Loading photos means querying the contacts provider
            // per row and holding bitmaps; the letter identifies the call just as well here, where
            // the name is already spelled out beside it.
            // The avatar carries the same origin badge the list row does, so a recording is marked
            // the same way wherever it is looked at. Before this the playback screen had no way of
            // saying a call was VoIP at all: the direction chip below only appears for phone calls,
            // and a VoIP recording has no direction to show.
            Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(accent.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val initial = item.contactName?.firstOrNull { it.isLetter() }?.uppercase()
                if (initial != null) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                } else {
                    // An unknown caller has no initial — "+97237406200" yielded an avatar reading "9",
                    // which says nothing. A glyph is honest about not knowing who this was.
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
                CallOriginBadge(item.direction, item.voipApp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Only when it adds something. The earlier test compared against the bidi-isolated
                // label, which never equals the raw number, so an unknown caller had their number
                // printed twice. The real question is whether a name is what the title is showing.
                item.number?.takeIf { item.contactName != null }?.let { number ->
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // The direction rides with the date instead of sitting at the end of the row.
                //
                // As a chip on the right it cost about 85dp beside two action icons, and the name —
                // the one thing this card exists to say — was left with barely a hundred: "גבריאל
                // 2b" came out as "גברי…" and the date wrapped mid-word. Down here the line is the
                // full width of the column and both fit comfortably.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.direction?.let {
                        DirectionChip(it)
                        Spacer(Modifier.width(8.dp))
                    }
                    playbackDate(item)?.let { date ->
                        Text(
                            text = date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Third from the right, so adding it left the two controls that were already here on the
            // exact pixels people had learned. Explicitly tinted in both states: an un-tinted M3 icon
            // resolves to CoralDeep in this scheme, which would read as an error rather than a star.
            IconButton(onClick = onToggleFavourite, modifier = Modifier.size(ACTION_TARGET)) {
                Icon(
                    imageVector = if (isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(
                        if (isFavourite) R.string.playback_unstar else R.string.playback_star
                    ),
                    tint = if (isFavourite) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            // The same one-slot control the list rows use — transcribe / in progress / read / retry
            // — rather than a second thing that means the same. Retry is transcribe: on this screen
            // there is one recording and one thing a failed run can do next.
            TranscriptActionButton(
                status = transcriptStatus,
                onTranscribe = onTranscribe,
                onOpen = onOpenTranscript,
                onRetry = onTranscribe,
                modifier = Modifier.size(ACTION_TARGET),
                // Was left at its default of 0, so this one spun without ever filling while the
                // identical control in the list showed a figure. A ring that turns for minutes and
                // never moves is indistinguishable from a hang — which is the whole reason the
                // percentage was added to that control in the first place.
                percent = transcriptPercent
            )

            // On the card rather than at the foot of the screen. It lived in a row below the note,
            // which meant scrolling to reach a control most people look for first — and briefly
            // meant not being able to reach it at all. Error-tinted and icon-only: it is one glyph
            // among a name and a chip, and the word "Delete" beside them would read as a label for
            // the call rather than as an action.
            IconButton(onClick = onDelete, modifier = Modifier.size(ACTION_TARGET)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.home_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * The action targets on the header card: 40dp, not the default 48dp.
 *
 * They share the row with the avatar, the name and the direction chip, and at 48 the pair took
 * another sixteen from a name that has to ellipsize anyway. Same size as the two actions on a list
 * row, and for the same reason recorded there.
 */
private val ACTION_TARGET = 40.dp

@Composable
private fun DirectionChip(direction: RecordingDirection) {
    val accent = MaterialTheme.colorScheme.primary
    val incoming = direction == RecordingDirection.INCOMING

    // The arrow alone, no word beside it.
    //
    // "outgoing" plus "Yesterday, 15:57" did not fit the column, and it was the date that lost —
    // truncated to "Yeste…", which is the half that actually identifies the call. An arrow pointing
    // out of the phone is not made clearer by the word "outgoing" printed next to it, so the word is
    // what goes. Screen readers still hear it: it moves to the content description.
    Surface(shape = CircleShape, color = accent.copy(alpha = 0.12f)) {
        Icon(
            imageVector = if (incoming) Icons.Filled.CallReceived else Icons.Filled.CallMade,
            contentDescription = stringResource(
                if (incoming) R.string.general_incoming else R.string.general_outgoing
            ),
            tint = accent,
            modifier = Modifier
                .padding(5.dp)
                .size(14.dp)
        )
    }
}

/** The transport: a scrubber, skip either way, play, and the speed the call is reviewed at. */
@Composable
private fun PlayerCard(
    peaks: FloatArray,
    /** The call's length as the list knows it, for before anything is loaded. */
    knownDurationMs: Int,
    playback: RecordingPlaybackController.PlaybackState,
    isActive: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onCycleSpeed: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    // Falls back to the length from the call log rather than showing 0:00 for a recording that is
    // simply not loaded yet. Nothing is playing until the play button is pressed, and a player that
    // claims a call is zero seconds long looks broken rather than idle.
    val duration = if (isActive && playback.durationMs > 0) playback.durationMs else knownDurationMs
    val position = if (isActive) playback.positionMs else 0

    CvCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Play, waveform and speed on one line, with the times tucked under the waveform they
            // belong to. The previous arrangement stacked the waveform, a times row, a row of three
            // transport buttons and a speed row — four full-width rows for a control strip, which
            // pushed the note card off the bottom of the screen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = {
                        when {
                            !isActive -> onPlay()
                            isPlaying -> onPause()
                            else -> onResume()
                        }
                    },
                    modifier = Modifier.size(PLAYER_CONTROL_SIZE),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.general_pause else R.string.general_resume
                            ),
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                // The times sit in this column rather than in a row of their own, so they start and
                // end exactly where the waveform does — they label it, and a full-width row would
                // put "0:00" under the play button instead.
                Column(modifier = Modifier.weight(1f)) {
                    // The waveform IS the scrubber. A Material Slider on top of it would be a second
                    // control for one job — and its thumb is far heavier than the inline player's
                    // thin bar, which is what made this card look like a different app.
                    WaveformBar(
                        peaks = peaks,
                        progress = if (duration > 0) position / duration.toFloat() else 0f,
                        onSeekTo = { fraction -> onSeek((fraction * duration).toInt()) }
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = TranscriptTimestamp.format(position.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = TranscriptTimestamp.format(duration.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                SpeedChip(speed = playback.speed, onClick = onCycleSpeed)
            }

            // Kept below, and kept large. Skipping back over a sentence you missed is the thing that
            // makes a long call reviewable, and it is pressed while listening rather than looked at
            // — so it stays a comfortable target instead of being folded into the row above.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = { onSkip(-PlaybackControls.SKIP_MS) },
                    enabled = isActive,
                    modifier = Modifier.size(52.dp),
                    // NOT the default: secondaryContainer in this theme is CoralDeep, so a tonal
                    // button comes out maroon and reads as an error — the same trap RecordingRow's
                    // selected-card colour records.
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = accent.copy(alpha = 0.14f),
                        contentColor = accent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay10,
                        contentDescription = stringResource(R.string.playback_skip_back),
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(Modifier.width(48.dp))

                FilledTonalIconButton(
                    onClick = { onSkip(PlaybackControls.SKIP_MS) },
                    enabled = isActive,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = accent.copy(alpha = 0.14f),
                        contentColor = accent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward10,
                        contentDescription = stringResource(R.string.playback_skip_forward),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

/**
 * The playback speed, as a chip between the elapsed and total times.
 *
 * A cycling control rather than a menu: one tap per step is fewer taps than opening a list to change
 * by one notch, and it is pressed repeatedly while listening.
 *
 * Sized deliberately rather than left to wrap. As a bare `TextButton` in its own row it had a large
 * target for free; sitting on a text row it needs stating, or it becomes a 16dp-tall thing to hit
 * while holding the phone one-handed.
 */
@Composable
private fun SpeedChip(speed: Float, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        onClick = onClick,
        shape = CircleShape,
        // Stated, not defaulted: this theme resolves M3's tonal container to CoralDeep, which comes
        // out maroon and reads as an error rather than as a control.
        color = accent.copy(alpha = SPEED_CHIP_ALPHA),
        contentColor = accent,
        modifier = Modifier.heightIn(min = PLAYER_CONTROL_SIZE)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(
                text = stringResource(R.string.playback_speed, formatSpeed(speed)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * One height for both ends of the control strip.
 *
 * Play and speed sit either side of the waveform, so a difference between them reads as a mistake
 * rather than as emphasis — the play button at 52dp beside a 36dp chip looked lopsided. Shared as a
 * constant because they are declared in two different composables and would otherwise drift apart
 * again the next time either is touched.
 *
 * The speed control stays a pill rather than a circle: its label is two characters wide and squeezing
 * "1.5×" into a circle would either clip it or force the circle wider than the play button.
 */
private val PLAYER_CONTROL_SIZE = 48.dp
private const val SPEED_CHIP_ALPHA = 0.14f

/**
 * The call's time, worded as the list words it — "Today 16:26", not "2026-08-20 16:26".
 *
 * Two screens describing the same call two different ways makes the user check whether they are
 * looking at the same recording.
 */
@Composable
private fun playbackDate(item: RecordingItem): String? {
    val millis = item.startedAtMillis ?: return item.displayDate
    return DateUtils.getRelativeDateTimeString(
        LocalContext.current,
        millis,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.WEEK_IN_MILLIS,
        0
    ).toString()
}

/**
 * What the user made of the call, in their own words.
 *
 * Not a duplicate of the transcript: a transcript records what was said, a note records what it meant
 * — the price agreed, the thing to chase. Saved as it is typed, because a note behind a Save button is
 * a note somebody loses.
 */
/**
 * The user's own labels for this call, and the way to add one.
 *
 * **Why tags exist beside a contact name.** A great many calls are with numbers that are in nobody's
 * address book — a clinic, a landlord, a claims line — so "who it was with" cannot find them again.
 * "The flat" can. This is the best-evidenced missing feature in the whole product survey, and the
 * request that carries it says exactly this.
 *
 * Chip colours are stated rather than defaulted: this app's scheme resolves several Material 3
 * defaults to a coral that reads as an error state, so a chip left to its own devices ships red.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagCard(
    tags: List<String>,
    knownTags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onRenameTag: (String, String) -> Unit,
    onDeleteTagEverywhere: (String) -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<String?>(null) }

    CvCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.playback_tags_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(8.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    // The chip body manages the tag everywhere; the × takes it off this call alone.
                    // Two different scopes, so they are two different targets rather than one
                    // control that sometimes means the library and sometimes means this recording.
                    onClick = { managing = tag },
                    label = { Text(tag) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.playback_tags_remove),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRemoveTag(tag) }
                        )
                    },
                    colors = InputChipDefaults.inputChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            AssistChip(
                onClick = { adding = true },
                label = { Text(stringResource(R.string.playback_tags_add)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.primary,
                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }

    managing?.let { tag ->
        ManageTagDialog(
            tag = tag,
            onDismiss = { managing = null },
            onRename = { newName ->
                managing = null
                onRenameTag(tag, newName)
            },
            onDeleteEverywhere = {
                managing = null
                onDeleteTagEverywhere(tag)
            }
        )
    }

    if (adding) {
        AddTagDialog(
            // Only tags this recording does not already carry: offering one that is already on the
            // chip row above invites a tap that does nothing.
            suggestions = knownTags.filterNot { known -> tags.any { it.equals(known, ignoreCase = true) } },
            onDismiss = { adding = false },
            onConfirm = { text ->
                adding = false
                onAddTag(text)
            }
        )
    }
}

/**
 * Typing a tag, with the ones already in use offered as chips.
 *
 * The suggestions are the point rather than a convenience: without them people coin *flat*, *the
 * flat* and *Flat* over three months and end up with three filters that each find a third of what
 * they wanted. Case alone is handled for them further down, in `TagName`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTagDialog(
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playback_tags_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= TagName.MAX_LENGTH) text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.playback_tags_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.take(MAX_TAG_SUGGESTIONS).forEach { suggestion ->
                            SuggestionChip(
                                onClick = { onConfirm(suggestion) },
                                label = { Text(suggestion) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.general_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        }
    )
}

/**
 * Renaming or removing a tag across the whole library.
 *
 * Scoped to *everywhere* on purpose, and said so in the copy. Taking a tag off this one call is the ×
 * on the chip; this dialog is the other thing entirely, and a user who cannot tell which they are
 * about to do will eventually do the wrong one to twenty recordings at once.
 *
 * Renaming onto an existing tag merges the two, which is the only way to repair a tag that was coined
 * twice — the outcome most people actually want when they open this.
 */
@Composable
private fun ManageTagDialog(
    tag: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDeleteEverywhere: () -> Unit
) {
    var text by remember(tag) { mutableStateOf(tag) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playback_tags_manage_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= TagName.MAX_LENGTH) text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.playback_tags_manage_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDeleteEverywhere,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.playback_tags_delete_everywhere)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(text) },
                enabled = text.isNotBlank() && text != tag
            ) { Text(stringResource(R.string.playback_tags_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        }
    )
}

/** Enough to jog a memory, few enough that the dialog does not become a list to read. */
private const val MAX_TAG_SUGGESTIONS = 8

/**
 * The field shows its own draft and saves a moment after the typing stops — see [NoteDraft] for the
 * swapped-letters bug that binding it to the stored value caused. "Saved" beside the title is the
 * only sign there is that the note is kept; without it the card read as a field with no Save button.
 */
@Composable
private fun NoteCard(note: String, recordingKey: String, onNoteChange: (String) -> Unit) {
    val draft = rememberNoteDraft(stored = note, key = recordingKey, onSave = onNoteChange)
    CvCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.playback_note_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (draft.text.isNotBlank() || !draft.isSaved) {
                Text(
                    text = stringResource(if (draft.isSaved) R.string.note_saved else R.string.note_saving),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.text,
            onValueChange = draft::edit,
            placeholder = { Text(stringResource(R.string.playback_note_hint)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            // Content direction: these notes are written in the same language the call was in.
            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
        )
    }
}

/** "1×", "1.5×" — a trailing ".0" on a speed reads like a measurement rather than a setting. */
private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString()
    else String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
