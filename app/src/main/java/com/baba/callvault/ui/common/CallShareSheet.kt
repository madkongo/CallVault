/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.share.CallSharePlan
import com.baba.callvault.data.share.ShareableCall
import com.baba.callvault.data.share.SharePart

/**
 * The one Share sheet, opened by Share on every page (except "Share a part", which keeps its own screen).
 *
 * It lists the call's recording, transcript and summary with the page's own already ticked, so sharing what the
 * page is about is still Share → Share, and anything else the call has is one tick away. See [CallSharePlan].
 *
 * @param calls what each call has, or null while that is still being looked up.
 * @param start the page's own part.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallShareSheet(
    calls: List<ShareableCall>?,
    start: SharePart,
    onDismiss: () -> Unit,
    onShare: (Set<SharePart>) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            if (calls == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                return@Column
            }
            val available = remember(calls) { CallSharePlan.available(calls) }
            var ticks by remember(calls) { mutableStateOf(CallSharePlan.initialTicks(start, available)) }
            val single = calls.singleOrNull()

            Text(
                text = if (single != null) stringResource(R.string.share_sheet_title_one, single.title)
                       else stringResource(R.string.share_sheet_title_many, calls.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val asFiles = CallSharePlan.asFiles(ticks)
            SharePart.entries.forEach { part ->
                val isAvailable = part in available
                ShareTick(
                    label = stringResource(labelOf(part)),
                    hint = when {
                        !isAvailable -> stringResource(
                            if (single != null) R.string.share_part_missing_one else R.string.share_part_missing_many,
                        )
                        part == SharePart.RECORDING -> stringResource(R.string.share_part_recording_hint)
                        asFiles -> stringResource(R.string.share_part_text_file_hint)
                        else -> stringResource(R.string.share_part_text_message_hint)
                    },
                    checked = part in ticks,
                    enabled = isAvailable,
                    onChange = { on -> ticks = if (on) ticks + part else ticks - part },
                )
            }
            Button(
                onClick = { onShare(ticks) },
                enabled = ticks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.share_sheet_button))
            }
        }
    }
}

private fun labelOf(part: SharePart): Int = when (part) {
    SharePart.RECORDING -> R.string.share_part_recording
    SharePart.TRANSCRIPT -> R.string.share_part_transcript
    SharePart.SUMMARY -> R.string.share_part_summary
}

@Composable
private fun ShareTick(label: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 6.dp),
    ) {
        // Stated, not left to Material's default, which renders coral in this theme.
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            val alpha = if (enabled) 1f else 0.45f
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
    }
}
