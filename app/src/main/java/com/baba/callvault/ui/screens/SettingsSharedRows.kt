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
import com.baba.callvault.summary.SummaryLanguage
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.ui.common.SummaryLanguagePicker
import com.baba.callvault.ui.common.ContactSelectionDialog
import com.baba.callvault.ui.common.SummaryRequirementsDialog
import com.baba.callvault.ui.viewmodels.ContactPickerState
import com.baba.callvault.ui.viewmodels.ContactPickerType
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

// Pieces the Settings screen ([ScopedSettingsContent]) uses, some shared with the wizard.

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
    )
}

/**
 * The language summaries are written in (BabApps item 135): "Same as the call (default)" or one fixed language.
 * It writes the `summary_language` preference that SummaryWorker already passes to SummaryLanguage.resolve as
 * the first, strongest answer; null is "same as the call", worked out per recording.
 */
@Composable
internal fun SummaryLanguageRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val stored = remember(updateTrigger) { preferences.getSummaryLanguage() }
    // The explanation sits behind the row's ⓘ like every other row's (WithInfo), not as inline text.
    WithInfo(stringResource(R.string.summary_language_label), stringResource(R.string.summary_language_hint)) {
        DropdownRow {
            SummaryLanguagePicker(
                label = stringResource(R.string.summary_language_label),
                selectedKey = SummaryLanguage.choiceKeyOf(stored),
                onSelected = { key ->
                    preferences.setSummaryLanguage(SummaryLanguage.languageOfChoiceKey(key))
                    actions.refreshSettings()
                },
            )
        }
    }
}

/**
 * The requirements popup, opened from the Summaries card's ⓘ (BabApps item 131; it replaced the "Show
 * requirements" switch). The "Don't show this again" box starts from what is stored, so this is also the
 * way back after ticking it in the download popup: untick it and tap "Got it".
 */
@Composable
internal fun SummaryRequirementsInfoDialog(preferences: AppPreferences, actions: SettingsActions, onDismiss: () -> Unit) {
    SummaryRequirementsDialog(
        model = SummaryModel.DEFAULT,
        onDismiss = onDismiss,
        onContinue = { dontAskAgain ->
            preferences.setSummaryConfirmRequirements(!dontAskAgain)
            actions.refreshSettings()
            onDismiss()
        },
        infoOnly = true,
        dontAskAgainInitially = !preferences.getSummaryConfirmRequirements(),
    )
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
