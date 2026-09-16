/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.ChannelMap
import com.baba.callvault.data.SpeakerNames
import com.baba.callvault.data.transcripts.export.ExportDocument
import com.baba.callvault.data.transcripts.export.TranscriptExport
import com.baba.callvault.data.transcripts.export.TranscriptFormat
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptWithSegments

/**
 * Where a transcript is being shown: over what raised it, or on a page of its own.
 *
 * A parameter rather than two composables, because the difference really is only the frame.
 * Everything inside — the lines, the summary strip, the transport, the export menu, the speaker
 * question — is one [TranscriptBody] call, and a second copy of that call is what this type exists to
 * prevent: two hosts with their own thirty-argument invocation would fall out of step the first time
 * one of them gained an argument the other did not.
 */
enum class TranscriptPresentation {

    /**
     * Over whatever raised it. The recordings list and a recording's own screen both use this: the
     * transcript is a look at something you are already standing on, and the list behind it keeps the
     * playing row tinted, so dismissing leaves the audio with something on screen that owns it.
     */
    Sheet,

    /**
     * A page, with a back arrow. What the Transcripts section opens, because there the transcript is
     * the destination rather than a detour — and nothing behind it says anything about playback, so
     * the caller has to stop the audio on the way out.
     */
    Screen,
}

/**
 * The transcript: near full height as a sheet, or filling a page of its own.
 *
 * As a [Sheet][TranscriptPresentation.Sheet] it is a [ModalBottomSheet] with `skipPartiallyExpanded`
 * rather than a full-screen dialog: it opens at nearly the full height as intended while keeping the
 * platform's dismiss gestures, which a custom screen would have to reimplement. As a
 * [Screen][TranscriptPresentation.Screen] the title moves into the app bar and [onDismiss] becomes
 * the back arrow.
 *
 * Segment text is deliberately never logged anywhere — it is the substance of a private call.
 *
 * @param transcript      the transcript to show, or null while it is still loading.
 * @param title           who the call was with, for the header.
 * @param presentation    which frame to draw it in; see [TranscriptPresentation].
 * @param onDismiss       close it: the sheet's dismiss gesture, or the page's back arrow. One
 *                        callback for both, so the caller has one piece of state to clear.
 * @param onSeekTo        start playing from a segment's start; a transcript you cannot navigate from
 *                        is only text, which is why the timestamps are stored at all. It starts
 *                        playback rather than merely seeking, so a tap works on a call that is not
 *                        loaded yet.
 * @param onRetranscribe  run it again, e.g. after switching to a better model.
 * @param onDelete        discard the text and keep the audio. Someone may want the recording without
 *                        a searchable transcript of what was said in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptView(
    transcript: TranscriptWithSegments?,
    /**
     * True once the database has answered, so a null [transcript] means there is none rather than
     * that it has not been read yet. The two used to be one answer and the reader was always told
     * the friendlier of them — see [com.baba.callvault.data.transcripts.TranscriptRepository.TranscriptRead].
     */
    isTranscriptSettled: Boolean,
    title: String,
    presentation: TranscriptPresentation = TranscriptPresentation.Sheet,
    modifier: Modifier = Modifier,
    /**
     * Where playback has reached, so the line being spoken can be lit and the transport bar knows
     * what to show. -1 when this recording is not the loaded track.
     */
    positionMs: Long = -1L,
    durationMs: Long = 0L,
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    /**
     * Writes the transcript out as a file in the chosen format and offers it to the share-sheet.
     *
     * The document is assembled here, where the segments, the speaker names and the stored summary
     * already are; the caller only has to put it on disk and raise the chooser.
     */
    onExport: (TranscriptFormat, ExportDocument) -> Unit = { _, _ -> },
    /**
     * The note and tags attached to this recording, so an export carries them.
     *
     * Passed in rather than read here for the same reason the speaker names are: the sheet renders
     * what it is given. They reach only the formats with somewhere to put them — Markdown and JSON —
     * and never a subtitle file.
     */
    note: String = "",
    tags: List<String> = emptyList(),
    onRetranscribe: () -> Unit,
    onDelete: () -> Unit,
    /**
     * How to name the two captured channels.
     *
     * Passed in rather than read here so the sheet stays a pure rendering of what it is given, and
     * because the answer belongs to the phone rather than to this transcript: it is learned from
     * ringback over several calls and applies to all of them at once.
     */
    speakerNames: SpeakerNames,
    /** The user says the mapping is this one, settling it for good. */
    onChooseSpeakerMap: (ChannelMap) -> Unit = {},
    /** Accepts what was worked out, which stops the offer without pinning anything. */
    onConfirmSpeakers: () -> Unit = {},
    /** The summary for this recording, so the sheet can show a stored one rather than rewrite it. */
    summaryState: SummaryCardState,
    onSummarise: () -> Unit,
    onStopSummary: () -> Unit
) {
    // Built once and handed to whichever frame is drawing. The single call site is the point: it is
    // what stops the two presentations becoming two argument lists that fall out of step.
    val body: @Composable ColumnScope.() -> Unit = {
        TranscriptBody(
            transcript = transcript,
            isTranscriptSettled = isTranscriptSettled,
            title = title,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isLoading = isLoading,
            onSeekTo = onSeekTo,
            onPlay = onPlay,
            onPause = onPause,
            onResume = onResume,
            onSeek = onSeek,
            onSkip = onSkip,
            onCopy = onCopy,
            onShare = onShare,
            onExport = onExport,
            note = note,
            tags = tags,
            onRetranscribe = onRetranscribe,
            onDelete = onDelete,
            speakerNames = speakerNames,
            onChooseSpeakerMap = onChooseSpeakerMap,
            onConfirmSpeakers = onConfirmSpeakers,
            summaryState = summaryState,
            onSummarise = onSummarise,
            onStopSummary = onStopSummary,
        )
    }

    when (presentation) {
        TranscriptPresentation.Sheet -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                body()
            }
        }

        // The title moves into the app bar rather than being drawn a second time, and the back arrow
        // is the same close the sheet's gesture performs, so the caller has one way to be told it is
        // over and one piece of state to clear.
        TranscriptPresentation.Screen -> CvScaffold(
            modifier = modifier.fillMaxSize(),
            title = title,
            onBack = onDismiss,
        ) { innerPadding ->
            // Inset with a Column rather than passed down: the body ends with a transport bar that
            // has to clear the navigation bar, and its list takes weight(1f), so the frame has to be
            // the thing that is the right height rather than the content inside it.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding(),
                    ),
                content = body,
            )
        }
    }
}

/**
 * Everything inside the frame: the summary strip, the lines, the transport and the actions.
 *
 * A [ColumnScope] extension because the transcript list takes `weight(1f)` — see the note on it —
 * and both frames are columns for exactly that reason.
 *
 * [title] is not drawn here. Each frame puts it where it belongs, a heading in the sheet and the app
 * bar on the page; it is still needed, because it names the call inside an exported document.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.TranscriptBody(
    transcript: TranscriptWithSegments?,
    isTranscriptSettled: Boolean,
    title: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isLoading: Boolean,
    onSeekTo: (Long) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onExport: (TranscriptFormat, ExportDocument) -> Unit,
    note: String,
    tags: List<String>,
    onRetranscribe: () -> Unit,
    onDelete: () -> Unit,
    speakerNames: SpeakerNames,
    onChooseSpeakerMap: (ChannelMap) -> Unit,
    onConfirmSpeakers: () -> Unit,
    summaryState: SummaryCardState,
    onSummarise: () -> Unit,
    onStopSummary: () -> Unit,
) {
    // Directly under the title, above the words. Someone who opened the transcript to find out
    // what a call was about is answered here without reading it — and a summary that already
    // exists is simply shown, because writing another costs ninety seconds of CPU for a page
    // that is already on the phone.
    SummarySheetStrip(
        state = summaryState,
        onCreate = onSummarise,
        onStop = onStopSummary,
        onSeek = onSeekTo
    )

    val segments = transcript?.segments.orEmpty()

    // Only where there is something to name. A transcript whose segments carry no speaker at
    // all — a mono capture, or a call whisper returned as one block — must not be asked a
    // question about two sides it never shows.
    val hasSpeakers = remember(segments, speakerNames) {
        segments.any { speakerNames.of(it.speaker) != null }
    }

    when {
        // Two different nothings, and until the Summaries page they were reported as one. Every
        // other way in is gated on a finished transcript, so a null here could only mean the query
        // had not come back — but deleting the text leaves the summary behind, and the summary's
        // own row then opens a page that would have said "Loading the transcript…" for ever.
        // "Transcribe again", below, is the way out of the second one.
        transcript == null -> Text(
            text = stringResource(
                if (isTranscriptSettled) R.string.transcript_none else R.string.transcript_loading
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
        )

        // Silence is a result, not a failure. A blank sheet would read as a bug.
        segments.isEmpty() -> Text(
            text = stringResource(R.string.transcript_no_speech),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
        )

        else -> {
            val active = remember(segments, positionMs) {
                if (positionMs < 0) -1
                else TranscriptFollow.activeIndex(segments.map { it.startMs }, positionMs)
            }
            val listState = rememberLazyListState()

            // Follow the speaker, but only while it is actually moving: scrolling the list out
            // from under someone who is reading it would be worse than not following at all.
            LaunchedEffect(active) {
                if (active >= 0) listState.animateScrollToItem(active)
            }

            if (hasSpeakers && speakerNames.isGuess) {
                SpeakerNamesHint(
                    names = speakerNames,
                    onChoose = onChooseSpeakerMap,
                    onConfirm = onConfirmSpeakers
                )
            }

            // fill = true, deliberately. With `fill = false` the list took only the height of
            // what it had composed so far, so the SHEET's height followed the content: it opened
            // at about half height, grew as you scrolled, hid the playback controls until a
            // growing list pushed them into view, collapsed back on rotation (a fresh measure)
            // and opened full the next time (a retained list state composes more at once).
            // mirror176 reported every one of those in #27 and called them intermittent, which
            // is what content-dependent looks like from outside. Filling the space makes the
            // height stable and the controls stay where they are put.
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(segments, key = { _, s -> s.id }) { index, segment ->
                    TranscriptLine(
                        segment = segment,
                        speaker = speakerNames.of(segment.speaker),
                        isActive = index == active,
                        onClick = { onSeekTo(segment.startMs) },
                        // The speaker's name goes with it. A quoted line without who said it is
                        // the thing a transcript exists to stop being ambiguous about.
                        onLongClick = {
                            val who = speakerNames.of(segment.speaker)?.let { "$it: " }.orEmpty()
                            onCopy(who + segment.text.trim())
                        }
                    )
                }
            }
        }
    }

    // Pinned below the transcript rather than scrolling with it: the point of these controls is
    // to pause or step back while reading, which is when they would have scrolled away.
    //
    // Present in every state, including "still loading" and "no speech was recognised" — the
    // recording exists and is playable whether or not there are words to show for it.
    TranscriptPlayerBar(
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        isLoading = isLoading,
        onPlay = onPlay,
        onPause = onPause,
        onResume = onResume,
        onSeek = onSeek,
        onSkip = onSkip,
        modifier = Modifier.padding(top = 4.dp)
    )

    // FlowRow, not Row: four actions do not fit on one line on a narrow screen in every locale,
    // and wrapping is better than squeezing labels until they ellipsize.
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val plain = segments.asPlainText(speakerNames)
        // One Share button rather than three that overlap. Copy is gone: copying the *whole*
        // transcript was never the common want, and long-pressing a line — which copies just that
        // sentence — is both what people actually reach for and impossible to do from a button.
        Box {
            var formatsShown by remember { mutableStateOf(false) }
            TextButton(onClick = { formatsShown = true }, enabled = segments.isNotEmpty()) {
                Text(stringResource(R.string.transcript_share))
            }
            // A menu rather than a screen: five formats is a choice, not a workflow, and the
            // labels are file extensions that need no explaining.
            DropdownMenu(expanded = formatsShown, onDismissRequest = { formatsShown = false }) {
                // First, and not a file: sending the words into a chat is the commonest share by
                // far, and an attachment there is worse than the text. The formats below it are
                // for somewhere that wants a document.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.transcript_share_text)) },
                    onClick = {
                        formatsShown = false
                        onShare(plain)
                    }
                )
                TranscriptFormat.entries.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format.label) },
                        onClick = {
                            formatsShown = false
                            onExport(
                                format,
                                ExportDocument(
                                    title = title,
                                    segments = segments,
                                    speakerNames = speakerNames,
                                    // Only a summary that is actually stored. A card that is
                                    // still being written, or was offered and declined, must not
                                    // reach the file as a half-finished account of the call.
                                    summary = (summaryState as? SummaryCardState.Ready)?.summary,
                                    note = note,
                                    tags = tags,
                                    language = transcript?.transcript?.language,
                                    model = transcript?.transcript?.modelId
                                )
                            )
                        }
                    )
                }
            }
        }
        TextButton(onClick = onRetranscribe) {
            Text(stringResource(R.string.transcript_retranscribe))
        }
        // The way back, once the question above has been answered and stops being asked.
        //
        // Without this, answering it was a one-way door: a mis-tap pinned an override that
        // outranks everything derived, silenced the bar for good, and left every transcript
        // from then on showing one person's words under the other's name with nothing on
        // screen to suggest it. That is the exact failure the two-call corroboration exists to
        // prevent, so it must not be reachable by a single tap either.
        //
        // Here rather than in Settings because this is where the mistake is visible.
        if (hasSpeakers && !speakerNames.isGuess && speakerNames.map != ChannelMap.UNKNOWN) {
            TextButton(
                onClick = {
                    onChooseSpeakerMap(
                        if (speakerNames.map == ChannelMap.A_IS_FAR) ChannelMap.B_IS_FAR
                        else ChannelMap.A_IS_FAR
                    )
                }
            ) {
                Text(stringResource(R.string.transcript_speakers_swap_names))
            }
        }
        // Error-tinted, because it destroys something: the audio survives but the text does not,
        // and getting it back costs a full re-run of the model.
        TextButton(
            onClick = onDelete,
            enabled = transcript != null,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(stringResource(R.string.transcript_delete))
        }
    }
}

/**
 * Asks who is who, until the user has said.
 *
 * The bar has two jobs and shows whichever one applies. **While nothing has been worked out** the
 * lines read "Speaker A" and "Speaker B" and the app has no opinion, so it simply asks which of the
 * two is the user — one tap, and both transcripts and every later one are named. **Once a mapping
 * has been worked out** — from a convention, corroborated over two calls — it says so and offers to
 * swap it, because that convention is right nearly always and wrong sometimes, and being wrong
 * means showing one person's words under the other's name.
 *
 * Asking first matters more than it looks: the convention needs two agreeing calls before it says
 * anything, so without this the very first labelled transcript showed anonymous sides with no way
 * to fix them, which reads as the feature being broken rather than as it being careful.
 *
 * It disappears for good once the user has answered either way. Agreeing is as much an answer as
 * correcting, and a bar that kept asking after being told would be worse than one that never asked.
 */
@Composable
private fun SpeakerNamesHint(
    names: SpeakerNames,
    onChoose: (ChannelMap) -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val unknown = names.map == ChannelMap.UNKNOWN
        Text(
            text = stringResource(
                if (unknown) R.string.transcript_speakers_which_is_you
                else R.string.transcript_speakers_guessed
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (unknown) {
            // Labelled with the very words on the lines above, so the choice is read off the screen
            // rather than remembered. Naming one side names the other: picking A as the user makes
            // B the far party.
            TextButton(onClick = { onChoose(ChannelMap.B_IS_FAR) }) { Text(names.sideA) }
            TextButton(onClick = { onChoose(ChannelMap.A_IS_FAR) }) { Text(names.sideB) }
        } else {
            TextButton(
                onClick = {
                    onChoose(
                        if (names.map == ChannelMap.A_IS_FAR) ChannelMap.B_IS_FAR
                        else ChannelMap.A_IS_FAR
                    )
                }
            ) {
                Text(stringResource(R.string.transcript_speakers_swap))
            }
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.transcript_speakers_correct))
            }
        }
    }
}

/**
 * One line: when it was said, optionally who said it, and the words.
 *
 * The speaker column is omitted entirely when unknown rather than rendered blank, so a transcript with
 * no speaker data reads as ordinary timestamped text instead of looking broken.
 */
@Composable
private fun TranscriptLine(
    segment: TranscriptSegmentEntry,
    speaker: String?,
    isActive: Boolean,
    onClick: () -> Unit,
    /**
     * Copies just this sentence.
     *
     * On a long press because the short press is already the useful one — it plays from here — and
     * quoting a single line is what people actually want from a transcript. Copying the whole thing
     * was a button nobody needed; this is the want it was standing in for.
     */
    onLongClick: () -> Unit
) {
    // The whole line mirrors, not just the words. Setting only the text's direction left the timestamp
    // column stranded on the left of a Hebrew transcript and short lines hugging the wrong edge,
    // because "start" still meant left. Providing the layout direction moves the column to the correct
    // side and makes start-alignment mean the right edge, in one stroke.
    //
    // Per line rather than per transcript: a call that switches language mid-way still reads correctly
    // throughout, and each line is judged only on what it actually says.
    val direction = if (BidiText.isRtl(segment.text)) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                // A tint rather than bolder text: re-weighting the line would reflow it, so every
                // line would twitch sideways as the highlight passed through.
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = TranscriptTimestamp.format(segment.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(TIMESTAMP_WIDTH)
            )

            Column(modifier = Modifier.weight(1f)) {
                speaker?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
                )
            }
        }
    }
}

/**
 * The transcript as shareable plain text, one timestamped line per segment.
 *
 * Named with [names] rather than with the stored `A`/`B`, so what leaves the phone reads the way the
 * screen does — and stays neutral for exactly as long as the screen does.
 */
private fun List<TranscriptSegmentEntry>.asPlainText(names: SpeakerNames): String =
    TranscriptExport.plainText(this, names)

private val TIMESTAMP_WIDTH = 56.dp
