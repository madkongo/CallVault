/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem


/**
 * The surface both merge cards live on.
 *
 * A plain [androidx.compose.material3.AlertDialog] cannot do this: it is laid out at the platform's
 * dialog width whatever is inside it, so the working state — a ring and two short lines — sat in a
 * card that had been sized for a list of calls and read as mostly empty. Owning the surface lets it
 * be wide while choosing and compact while working, and [androidx.compose.animation.animateContentSize]
 * makes that a change of shape rather than one dialog replacing another.
 */
@Composable
private fun MergeCard(
    compact: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Back is blocked while working for the same reason a tap outside is: it would hide a
            // job that is still deleting the user's original calls.
            dismissOnBackPress = !compact,
            dismissOnClickOutside = !compact,
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .then(if (compact) Modifier.width(COMPACT_WIDTH) else Modifier.fillMaxWidth(0.92f))
                .animateContentSize()
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp), content = content)
        }
    }
}

/** Wide enough for a ring and a line of detail, and no wider. */
private val COMPACT_WIDTH = 284.dp

/**
 * Chooses which calls to merge into one, and in what order.
 *
 * The recording the menu was opened from is ① and cannot be moved or unticked — a merge is always
 * "this call, then these". Everything else is ticked in the order it should play, and takes the next
 * number as it goes. **The numbers are the whole safety mechanism:** the order is deliberately not
 * chronological, so the only thing stopping someone assembling a conversation backwards is being
 * able to see the order they have built before they commit to it. The summary line at the bottom
 * spells the same thing out a second way.
 *
 * @param candidates every other call with this number that is on the phone, newest first.
 * @param onConfirm  receives the ticked names in tick order — the primary is not included.
 */
@Composable
fun MergeCallsDialog(
    primary: RecordingItem,
    candidates: List<RecordingItem>,
    /** Whether the calls being merged in will be kept — [AppPreferences.isKeepOriginalsAfterMerge]. */
    keepOriginals: Boolean,
    /** Null while the user is still choosing; set once the merge starts, and the card morphs. */
    progress: MergeProgressState?,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onCloseProgress: () -> Unit,
) {
    // A list, not a set: ticking order is the merge order, so it has to be remembered.
    var picked by remember { mutableStateOf(listOf<String>()) }
    val who = primary.contactName ?: primary.number ?: primary.displayName
    val totalSeconds = (primary.durationSeconds ?: 0L) +
        picked.sumOf { name -> candidates.firstOrNull { it.displayName == name }?.durationSeconds ?: 0L }

    MergeCard(
        compact = progress != null,
        // Not dismissible once it is working: tapping away would hide a job that is still deleting
        // the user's original calls.
        onDismissRequest = { if (progress == null) onDismiss() },
    ) {
        if (progress != null) {
            MergeProgressBody(progress)
            MergeProgressFooter(progress, onCloseProgress)
            return@MergeCard
        }

        Text(
            text = stringResource(R.string.merge_title, who),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.size(16.dp))
        MergeRow(item = primary, badge = 1, enabled = false, checked = true, onToggle = {})
        Text(
            text = stringResource(R.string.merge_primary_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 44.dp, bottom = 8.dp)
        )
        HorizontalDivider()
        Spacer(Modifier.size(8.dp))
        if (candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.merge_no_candidates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.merge_pick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(4.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(candidates, key = { it.displayName }) { item ->
                    val at = picked.indexOf(item.displayName)
                    MergeRow(
                        item = item,
                        // +2 because the primary is 1 and this list starts after it.
                        badge = if (at >= 0) at + 2 else null,
                        enabled = true,
                        checked = at >= 0,
                        onToggle = {
                            picked = if (at >= 0) picked - item.displayName
                            else picked + item.displayName
                        }
                    )
                }
            }
        }
        // Always laid out, only faded. Showing this block on the first tick grew the card, which
        // slid the Cancel and Merge buttons down under the finger — the stutter at the bottom of the
        // list. Reserving the space costs nothing and means ticking changes no geometry at all.
        Column(modifier = Modifier.alpha(if (picked.isEmpty()) 0f else 1f)) {
            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.merge_summary, picked.size + 1, picked.size + 1, formatDuration(totalSeconds)
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            // Say what merging does to the other calls, here, at the moment of deciding. The setting
            // that controls it lives in Storage, and someone who has never opened that screen would
            // otherwise find out by noticing three calls missing.
            Text(
                text = stringResource(
                    if (keepOriginals) R.string.merge_note_will_keep else R.string.merge_note_will_delete
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
            TextButton(enabled = picked.isNotEmpty(), onClick = { onConfirm(picked) }) {
                Text(stringResource(R.string.merge_confirm))
            }
        }
    }
}

/**
 * One call in the merge list: an empty ring when untouched, a numbered disc once ticked.
 *
 * Both marks are the same size inside the same fixed box, and the row's padding does not change with
 * them. The first version used a Material Checkbox, which carries its own 48dp touch target while
 * the numbered disc did not — so every row jumped in height as it was ticked and the list visibly
 * reflowed under the finger doing the ticking.
 */
@Composable
private fun MergeRow(
    item: RecordingItem,
    badge: Int?,
    enabled: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // toggleable, not clickable: TalkBack then says "Checkbox, checked" rather than only
            // "double-tap to activate" with no hint of whether the call is in the merge. The primary row
            // passes enabled = false and reads as checked and unavailable, which is what it is.
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box(modifier = Modifier.size(MARK_BOX), contentAlignment = Alignment.Center) {
            if (badge != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(MARK_DISC)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = badge.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(MARK_DISC)
                        .clip(CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = item.displayDate ?: item.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatDuration(item.durationSeconds ?: 0L),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The mark's slot and the disc inside it. Fixed, so ticking never changes a row's height. */
private val MARK_BOX = 34.dp
private val MARK_DISC = 26.dp

/** m:ss, or h:mm:ss once a conversation runs past an hour — which merged ones often will. */
private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Confirms taking a merged call apart, listing exactly what comes back.
 *
 * The list is not decoration. Merging an already-merged call flattens it, so taking apart a call
 * assembled from a merge plus one more returns **three** calls rather than the two that were ticked.
 * Naming them is what stops that being a surprise.
 */
@Composable
fun UnMergeDialog(
    partLabels: List<String>,
    /** Null while confirming; set once the un-merge starts, and the card morphs. */
    progress: MergeProgressState?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCloseProgress: () -> Unit,
) {
    MergeCard(
        compact = progress != null,
        onDismissRequest = { if (progress == null) onDismiss() },
    ) {
        if (progress != null) {
            MergeProgressBody(progress)
            MergeProgressFooter(progress, onCloseProgress)
            return@MergeCard
        }

        Text(
            text = stringResource(R.string.unmerge_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.unmerge_message))
        Spacer(Modifier.size(8.dp))
        partLabels.forEachIndexed { i, label ->
            Text(
                text = "${i + 1}.  $label",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.unmerge_confirm)) }
        }
    }
}
