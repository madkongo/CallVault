/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.calls.CallHistory
import com.baba.callvault.data.calls.CallKind
import com.baba.callvault.data.calls.PhoneCall
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.ui.common.CvCard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "Recordings | All calls" at the top of the Recordings page (option D, chosen by the maintainer 2026-11-03). */
@Composable
internal fun CallsViewSwitch(callsView: Boolean, onChange: (Boolean) -> Unit) {
    val labels = listOf(stringResource(R.string.home_view_recordings), stringResource(R.string.home_view_all_calls))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = (i == 1) == callsView,
                onClick = { onChange(i == 1) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                // Stated, not defaulted: M3's selected colour is secondaryContainer, which in this scheme is
                // coral, so the default drew a pink tab in a teal app (OP9, 2026-11-03).
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    activeBorderColor = MaterialTheme.colorScheme.primary,
                ),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** All / Recorded / Not recorded / Missed: one tap each, like the starred toggle on the recordings list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallFilterChips(selected: CallHistory.Filter, onSelect: (CallHistory.Filter) -> Unit) {
    val options = listOf(
        CallHistory.Filter.ALL to R.string.home_calls_filter_all,
        CallHistory.Filter.RECORDED to R.string.home_calls_filter_recorded,
        CallHistory.Filter.NOT_RECORDED to R.string.home_calls_filter_not_recorded,
        CallHistory.Filter.MISSED to R.string.home_calls_filter_missed,
    )
    // Wraps rather than clips: "Nicht aufgezeichnet" does not fit beside three other chips.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (filter, label) ->
            val on = filter == selected
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .semantics { role = Role.Tab; this.selected = on }
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** How a recorded call was recorded, for the start of its subtitle: "Phone call", or the app's name. */
@Composable
internal fun originLabelOf(item: RecordingItem): String = when {
    item.voipApp != null -> item.voipApp
    RecordingsRepository.isCarrierCall(item.displayName) -> stringResource(R.string.home_call_origin_phone)
    else -> stringResource(R.string.voip_missed_label)
}

/** A call in the log that CallVault did not record: answered but not recorded, missed, or declined. */
@Composable
internal fun CallHistoryRow(call: PhoneCall, simLabel: String?) {
    val name = call.cachedName ?: call.number ?: stringResource(R.string.home_call_unknown_number)
    val (icon, status) = when (call.kind) {
        CallKind.MISSED -> Icons.AutoMirrored.Filled.CallMissed to stringResource(R.string.home_call_missed)
        CallKind.REJECTED -> Icons.Filled.CallEnd to stringResource(R.string.home_call_declined)
        CallKind.OUTGOING -> Icons.AutoMirrored.Filled.CallMade to stringResource(R.string.home_call_not_recorded)
        else -> Icons.AutoMirrored.Filled.CallReceived to stringResource(R.string.home_call_not_recorded)
    }
    val meta = listOfNotNull(
        callTimeLabel(call.startedAt),
        call.durationSeconds.takeIf { it > 0 }?.let { formatCallDuration(it) },
        simLabel,
    ).joinToString(" · ")
    CvCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(status, meta).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Shown when "All calls" has nothing: no permission to read the call log, or an empty log. */
@Composable
internal fun EmptyCalls(hasPermission: Boolean) {
    CvCard(contentPadding = PaddingValues(vertical = 28.dp, horizontal = 24.dp)) {
        Text(
            stringResource(if (hasPermission) R.string.home_calls_empty else R.string.home_calls_no_permission),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Today 14:30", "Yesterday 09:15", or "29/07/26 14:30" — the same wording as the recordings list. */
@Composable
internal fun callTimeLabel(millis: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay(day, today) -> "${stringResource(R.string.home_date_today)} $time"
        sameDay(day, yesterday) -> "${stringResource(R.string.home_date_yesterday)} $time"
        else -> SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(millis))
    }
}

/** "12:41" under an hour, "1:05:30" beyond it. */
internal fun formatCallDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
