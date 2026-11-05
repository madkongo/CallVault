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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.transcription.cloud.TranscriptionService

/**
 * The one confirmation behind every "Transcribe again" (#72): the row menu and the selection bar both open it.
 *
 * It lets the user change what usually caused the bad transcript (the language, or the engine), says how long
 * it will take, and offers to delete a summary written from the old text. The engine choice shows only when
 * more than one engine is ready; a single ready engine is simply used.
 *
 * @param title the recording's name for one row; null when several are picked ([count]).
 * @param services the engines that can run now, the setting first ([TranscriptionRouting.readyServices]).
 * @param estimateMsFor how long the whole job takes on an engine, or null while unknown.
 */
@Composable
fun TranscribeAgainDialog(
    title: String?,
    count: Int,
    languageSetting: String?,
    services: List<TranscriptionService>,
    estimateMsFor: (TranscriptionService) -> Long?,
    hasSummary: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (language: String, service: TranscriptionService, deleteSummary: Boolean) -> Unit,
) {
    var language by remember(languageSetting) { mutableStateOf(TranscriptionLabels.preselectedLanguageKey(languageSetting)) }
    var service by remember(services) { mutableStateOf(services.first()) }
    var deleteSummary by remember(hasSummary) { mutableStateOf(hasSummary) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null) },
        iconContentColor = MaterialTheme.colorScheme.primary,
        title = { Text(stringResource(R.string.transcribe_again_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = if (title != null) stringResource(R.string.transcribe_again_message_one, title)
                           else stringResource(R.string.transcribe_again_message_many, count),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TranscriptionLanguagePicker(
                    label = stringResource(R.string.transcription_language_label),
                    selectedKey = language,
                    onSelected = { language = it },
                )
                if (services.size > 1) {
                    Text(
                        text = stringResource(R.string.transcribe_again_run_on),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        services.forEach { option ->
                            FilterChip(
                                selected = option == service,
                                onClick = { service = option },
                                label = { Text(serviceName(option)) },
                                // Stated, not left to Material's default, which renders coral in this theme.
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
                estimateMsFor(service)?.let { ms ->
                    Text(
                        text = stringResource(R.string.transcribe_again_estimate, formatEstimate(ms)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (hasSummary) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .toggleable(value = deleteSummary, role = Role.Checkbox, onValueChange = { deleteSummary = it }),
                    ) {
                        Checkbox(checked = deleteSummary, onCheckedChange = null)
                        Text(
                            text = stringResource(R.string.transcribe_again_delete_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(language, service, deleteSummary) }) {
                Text(stringResource(R.string.transcribe_language_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        },
    )
}

/** The name an engine goes by elsewhere in the app, so the dialog and Settings say the same thing. */
@Composable
private fun serviceName(service: TranscriptionService): String = when (service) {
    TranscriptionService.ON_DEVICE -> stringResource(R.string.transcription_where_on_device)
    TranscriptionService.CUSTOM -> stringResource(R.string.transcription_where_custom)
    else -> service.preset?.displayName ?: service.key
}
