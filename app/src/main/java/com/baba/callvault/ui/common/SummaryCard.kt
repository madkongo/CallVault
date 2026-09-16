/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.summary.SummaryBlocker

/** What the summary area of the playback screen is showing. */
sealed interface SummaryCardState {

    /** A summary exists. */
    data class Ready(val summary: CallSummary) : SummaryCardState

    /** No summary, and one can be asked for. */
    data object Offered : SummaryCardState

    /** Being written right now. */
    data class Running(val percent: Int) : SummaryCardState

    /** No summary and one cannot be asked for yet. */
    data class Blocked(val reason: SummaryBlocker) : SummaryCardState

    /** The last attempt produced nothing usable. */
    data object Failed : SummaryCardState
}

/**
 * The summary, on the recording it belongs to.
 *
 * **A summary is part of a recording, not a place of its own**, so it sits on the playback screen
 * above the transcript row — it answers the question the transcript answers more slowly.
 *
 * Sections rather than a paragraph, because the decisions and the follow-ups are what someone
 * returns to a call for and a paragraph buries them. Items that begin `[m:ss]` render the stamp as a
 * chip and **seek on tap**: a summary made of jump points is a table of contents for a conversation,
 * and it is the one thing this can offer that reading the transcript cannot.
 *
 * Empty lists render as nothing at all, never as "No decisions" — a call that decided nothing should
 * look like a shorter card, not like a form with blanks in it.
 */
@Composable
fun SummaryCard(
    state: SummaryCardState,
    onCreate: () -> Unit,
    onRedo: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    CvCard(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        Header()
        Spacer(Modifier.height(12.dp))

        when (state) {
            is SummaryCardState.Ready -> ReadySummary(state.summary, onRedo, onSeek)
            SummaryCardState.Offered -> ActionRow(
                label = stringResource(R.string.summary_card_create),
                supporting = stringResource(R.string.summary_card_create_subtitle),
                onClick = onCreate
            )
            is SummaryCardState.Running -> RunningRow(state.percent, onStop)
            is SummaryCardState.Blocked -> Explanation(stringResource(reasonTextOf(state.reason)))
            SummaryCardState.Failed -> {
                Explanation(stringResource(R.string.summary_card_failed))
                Spacer(Modifier.height(4.dp))
                ActionRow(
                    label = stringResource(R.string.summary_card_create),
                    supporting = null,
                    onClick = onCreate
                )
            }
        }
    }
}

/**
 * The same summary, folded into the transcript sheet.
 *
 * The sheet is for reading the transcript, so this stays one row until asked. **A stored summary is
 * shown, never rewritten** — it cost about ninety seconds of the phone's CPU, and the sheet is a
 * place to read it rather than a second button that spends that again.
 *
 * Expanding reuses the card's own rendering, so the two cannot drift, and taps on a `[m:ss]` seek
 * through the sheet's existing `onSeekTo` — the same path a tapped transcript line already takes.
 */
@Composable
fun SummarySheetStrip(
    state: SummaryCardState,
    onCreate: () -> Unit,
    onStop: () -> Unit,
    /**
     * Plays from a cited timestamp, or **null when there is no audio to play** — a transcript can
     * outlive its recording, and a citation chip that answered a tap with nothing would be the same
     * dead control the transport below it no longer draws.
     */
    onSeek: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    // Collapsed by default, because the transcript is what the page is for. Opened from the Summaries
    // list it is the other way round: the summary IS the thing tapped, and a one-line strip above
    // someone else's words is not what that tap asked for.
    var expanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        when (state) {
            is SummaryCardState.Ready -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // Collapsed, the intent is the summary in one line — more use than the word
                        // "Summary", which says nothing the icon has not already said.
                        text = state.summary.intent.ifEmpty { stringResource(R.string.summary_card_title) },
                        style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(R.string.summary_card_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (expanded) {
                    ReadySummary(state.summary, onRedo = onCreate, onSeek = onSeek)
                    Spacer(Modifier.height(8.dp))
                }
            }

            SummaryCardState.Offered, SummaryCardState.Failed -> ActionRow(
                label = stringResource(R.string.summary_card_create),
                supporting = stringResource(R.string.summary_card_create_subtitle),
                onClick = onCreate
            )

            is SummaryCardState.Running -> RunningRow(state.percent, onStop)

            // Silent rather than explaining itself here. Reading a transcript is the one moment the
            // user is *not* asking about summaries, and "transcribe this call first" is nonsense on
            // a screen that is showing the transcript.
            is SummaryCardState.Blocked -> Unit
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.summary_card_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ReadySummary(summary: CallSummary, onRedo: () -> Unit, onSeek: ((Long) -> Unit)?) {
    // The call's own language, which is rarely the app's. Content direction rather than a fixed one
    // so a Hebrew summary lays out right-to-left inside an English UI.
    if (summary.intent.isNotEmpty()) {
        Text(
            text = summary.intent,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
    }
    Text(
        text = summary.summary,
        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Section(R.string.summary_card_decisions, summary.decisions, onSeek)
    Section(R.string.summary_card_actions, summary.actionItems, onSeek)
    Section(R.string.summary_card_key_points, summary.keyPoints, onSeek)
    Section(R.string.summary_card_facts, summary.keyFacts, onSeek)

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Not optional. This is the honest label for a machine's reading of a machine's
        // transcription of a call, it explains without apologising why a summary can be wrong, and
        // it is the thing that sends someone to the transcript when it looks off.
        Text(
            text = stringResource(R.string.summary_card_provenance),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRedo) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.summary_card_redo))
        }
    }
}

/** A titled list, or nothing at all when the list is empty. */
@Composable
private fun Section(titleRes: Int, items: List<String>, onSeek: ((Long) -> Unit)?) {
    if (items.isEmpty()) return

    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
    items.forEach { item ->
        Spacer(Modifier.height(6.dp))
        SummaryItem(item, onSeek)
    }
}

/** One bullet. A `[m:ss]` prefix becomes a chip that seeks. */
@Composable
private fun SummaryItem(item: String, onSeek: ((Long) -> Unit)?) {
    val stamp = TranscriptTimestamp.parseLeading(item)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (stamp != null) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = STAMP_ALPHA),
                shape = RoundedCornerShape(6.dp),
                // Still drawn without audio — it says when in the call the point was made, which is
                // worth reading on its own — but not clickable, so it cannot ripple and do nothing.
                modifier = if (onSeek == null) Modifier
                           else Modifier.clickable { onSeek(stamp.millis) }
            ) {
                Text(
                    text = TranscriptTimestamp.format(stamp.millis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = stamp?.text ?: item,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionRow(label: String, supporting: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        if (supporting != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RunningRow(percent: Int, onStop: () -> Unit) {
    // Both colours stated: left to the theme, M3 resolves the track to CoralDeep here and the bar
    // renders teal-on-pink, which reads as an error rather than as progress.
    val accent = MaterialTheme.colorScheme.primary

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.summary_card_running, percent),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onStop) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.summary_card_stop))
        }
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { percent / PERCENT },
        modifier = Modifier.fillMaxWidth(),
        color = accent,
        trackColor = accent.copy(alpha = TRACK_ALPHA)
    )
}

@Composable
private fun Explanation(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Why it cannot be summarised, in words the user can act on. */
private fun reasonTextOf(reason: SummaryBlocker): Int = when (reason) {
    SummaryBlocker.NO_TRANSCRIPT -> R.string.summary_card_blocked_no_transcript
    SummaryBlocker.TRANSCRIPT_UNFINISHED -> R.string.summary_card_blocked_unfinished
    SummaryBlocker.TRANSCRIPT_EMPTY -> R.string.summary_card_blocked_empty
    SummaryBlocker.MODEL_MISSING -> R.string.summary_card_blocked_model
    SummaryBlocker.TRANSCRIBING -> R.string.summary_card_blocked_transcribing
    SummaryBlocker.SUMMARISING -> R.string.summary_card_blocked_summarising
}

private const val PERCENT = 100f
private const val STAMP_ALPHA = 0.14f
private const val TRACK_ALPHA = 0.20f
