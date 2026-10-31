/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.summary.SummaryModel

/**
 * What downloading the summariser actually costs, said before it starts.
 *
 * **Every number here was measured on the OP12.** They come from [SummaryModel] rather than from the
 * string, so the copy cannot drift from what was actually measured — an earlier draft of the plan
 * quoted 2 GB of memory, from a reading taken after the model had been freed, and 3.5 GB is the real
 * figure.
 *
 * The timing is deliberately vague. A short call was measured at about 97 s, or 130 s with per-line
 * timestamps; a ten-minute call is an *extrapolation* from 500-character samples, so quoting a
 * per-minute rate would present arithmetic as measurement.
 *
 * Not a warning that tries to talk anyone out of it. The decision is theirs — a phone that cannot
 * cope will be slow or fail, and saying so plainly is more use than a refusal.
 */
@Composable
fun SummaryRequirementsDialog(
    model: SummaryModel,
    onDismiss: () -> Unit,
    onContinue: (dontAskAgain: Boolean) -> Unit
) {
    var dontAskAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.summary_requirements_heading)) },
        text = {
            Column {
                Requirement(
                    Icons.Filled.Lock,
                    stringResource(R.string.summary_requirements_private)
                )
                Requirement(
                    Icons.Filled.Download,
                    stringResource(R.string.summary_requirements_download, model.sizeBytes.toGb())
                )
                Requirement(
                    Icons.Filled.Memory,
                    stringResource(R.string.summary_requirements_memory, model.peakMemoryBytes.toGb())
                )
                Requirement(
                    Icons.Filled.Schedule,
                    stringResource(R.string.summary_requirements_time)
                )
                Requirement(
                    Icons.Filled.PhoneAndroid,
                    stringResource(R.string.summary_requirements_phone)
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.summary_model_licence),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                // Undoable from Settings. A dialog that can permanently remove itself is a trap.
                // The row toggles, so the label and the box are one TalkBack stop ("Don't ask again,
                // Checkbox, not checked") instead of an unlabelled checkbox beside loose text.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = dontAskAgain,
                            role = Role.Checkbox,
                            onValueChange = { dontAskAgain = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = dontAskAgain,
                        onCheckedChange = null,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                    Text(
                        text = stringResource(R.string.summary_requirements_dont_ask),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onContinue(dontAskAgain) }) {
                Text(stringResource(R.string.summary_requirements_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.summary_requirements_cancel))
            }
        }
    )
}

@Composable
private fun Requirement(icon: ImageVector, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(10.dp))
}

/** Bytes as gigabytes, for copy that talks in the units a person would. */
private fun Long.toGb(): Float = this / 1_000_000_000f
