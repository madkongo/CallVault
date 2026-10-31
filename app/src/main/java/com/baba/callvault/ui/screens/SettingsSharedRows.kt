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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.common.ContactSelectionDialog
import com.baba.callvault.ui.viewmodels.ContactPickerState
import com.baba.callvault.ui.viewmodels.ContactPickerType
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

// Pieces both Settings screens use: the classic [SettingsContent] and the new [ScopedSettingsContent].

/**
 * Back up or restore settings — a section-less row at the very bottom of Settings. Export writes a JSON
 * file of your device-independent preferences; Import reads one back (after a confirm).
 */
@Composable
internal fun ExportImportRow(onExport: () -> Unit, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_backup_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_backup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_export))
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_import))
            }
        }
    }
}

/**
 * The summariser's rows with the Settings-only "ask me first" switch. Shared by the classic screen's
 * Transcription & summaries section and the new screen's Summaries group.
 */
@Composable
internal fun SummarySettingsRows(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SummaryRows(
        updateTrigger = updateTrigger,
        onDownload = { model, network -> actions.downloadSummaryModel(model, network) },
        onCancel = { actions.cancelSummaryModelDownload(it) },
        onDelete = { actions.deleteSummaryModel(it) },
        onImport = { model, uri -> actions.importModel(model, uri) },
        onCancelImport = { actions.cancelModelImport(it) },
        onBackup = { model, uri -> actions.backUpModel(model, uri) },
        onCancelBackup = { actions.cancelModelBackup(it) }
    ) {
        // Settings-only, and passed in rather than living in SummaryRows because
        // the wizard renders those same rows and has no business offering a
        // preference about a dialog it is showing for the first time.
        //
        // Its home is here so the dialog's "don't show this again" can be undone
        // — a dialog that can permanently remove itself with no way back would be
        // a trap. Same reasoning as the transcription confirmation switch above.
        val askFirst = remember(updateTrigger) {
            preferences.getSummaryConfirmRequirements()
        }
        SettingsToggleRow(
            icon = Icons.Filled.Info,
            label = stringResource(R.string.summary_requirements_heading),
            description = stringResource(R.string.summary_requirements_toggle_description),
            checked = askFirst,
            onCheckedChange = {
                preferences.setSummaryConfirmRequirements(it)
                actions.refreshSettings()
            }
        )
    }
}

/** The open-source licences, full screen. Shared by both Settings screens. */
@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Text(
                    text = stringResource(R.string.general_licenses),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )

                val libraries by produceLibraries(R.raw.aboutlibraries)
                LibrariesContainer(libraries,Modifier
                    .fillMaxSize()
                    .weight(1f),
                    showAuthor = true, showLicenseBadges = true, showFundingBadges = false, showVersion = true, showDescription = true)
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(8.dp)
                ) {
                    Text(stringResource(R.string.general_close))
                }
            }
        }
    }
}

/** The contact-picker dialog that sits on top of either Settings screen while it is open. */
@Composable
internal fun SettingsContactPicker(
    contactPickerState: ContactPickerState?,
    onConfirmContacts: (Set<String>) -> Unit,
    onDismissContacts: () -> Unit,
) {
    contactPickerState?.let { picker ->
        ContactSelectionDialog(
            title = when (picker.type) {
                ContactPickerType.INCOMING ->
                    if (picker.recordOnly) stringResource(R.string.settings_select_record_only_incoming)
                    else stringResource(R.string.settings_select_contacts_incoming)
                ContactPickerType.OUTGOING ->
                    if (picker.recordOnly) stringResource(R.string.settings_select_record_only_outgoing)
                    else stringResource(R.string.settings_select_contacts_outgoing)
            },
            contacts = picker.contacts,
            initialSelection = picker.selectedNumbers,
            onConfirm = onConfirmContacts,
            onDismiss = onDismissContacts
        )
    }
}
