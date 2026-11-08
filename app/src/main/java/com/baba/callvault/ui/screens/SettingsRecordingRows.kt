/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.common.FileNameFormatDialog
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.utils.fileNameTemplateExample
import com.baba.callvault.utils.CustomFileNameTemplate
import com.baba.callvault.utils.presetForTemplateOrFirst

// The Recording section's rows, one composable each, so the classic Recording section and the new screen's
// "Calls to record" / "Calls to skip" / "Sound and file names" groups draw the very same rows. Each reads its
// own value from preferences, keyed on updateTrigger, exactly as the section did.

/** The master switch for phone calls. */
@Composable
internal fun CarrierRecordingRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val carrierRecording = remember(updateTrigger) { preferences.isCarrierRecordingEnabled() }
    SettingsToggleRow(
        icon = Icons.Filled.Smartphone,
        label = stringResource(R.string.settings_carrier_recording),
        description = stringResource(R.string.settings_carrier_recording_description),
        checked = carrierRecording,
        onCheckedChange = { actions.setCarrierRecording(it) }
    )
}

@Composable
internal fun AutoRecordIncomingRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val autoRecordIncoming = remember(updateTrigger) { preferences.isAutoRecordIncomingEnabled() }
    SettingsToggleRow(
        icon = Icons.AutoMirrored.Filled.CallReceived,
        label = stringResource(R.string.settings_auto_record_incoming),
        checked = autoRecordIncoming,
        onCheckedChange = { actions.setAutoRecordIncoming(it) }
    )
}

@Composable
internal fun AutoRecordOutgoingRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val autoRecordOutgoing = remember(updateTrigger) { preferences.isAutoRecordOutgoingEnabled() }
    SettingsToggleRow(
        icon = Icons.AutoMirrored.Filled.CallMade,
        label = stringResource(R.string.settings_auto_record_outgoing),
        checked = autoRecordOutgoing,
        onCheckedChange = { actions.setAutoRecordOutgoing(it) }
    )
}

/**
 * Phone calls only: an app call has no answer signal to wait for (AnswerWait). Not nested under the
 * automatic switch because a call started from the Record prompt is already past the dial by the time
 * the button is pressed — this only ever changes an automatic start.
 */
@Composable
internal fun RecordFromAnswerRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val recordFromAnswer = remember(updateTrigger) { preferences.isRecordFromAnswerEnabled() }
    SettingsToggleRow(
        icon = Icons.Filled.PhoneInTalk,
        label = stringResource(R.string.settings_record_from_answer),
        description = stringResource(R.string.settings_record_from_answer_description),
        checked = recordFromAnswer,
        onCheckedChange = { actions.setRecordFromAnswer(it) }
    )
}

@Composable
internal fun IgnoreAnonymousIncomingRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val ignoreAnonymousIncoming = remember(updateTrigger) { preferences.isIgnoreAnonymousIncomingEnabled() }
    SettingsToggleRow(
        label           = stringResource(R.string.settings_ignore_anonymous_incoming),
        checked         = ignoreAnonymousIncoming,
        onCheckedChange = { actions.setIgnoreAnonymousIncoming(it) }
    )
}

/** Enabled only while anonymous incoming calls are ignored, as on the classic screen. */
@Composable
internal fun IgnoreCrossCountryIncomingRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val ignoreAnonymousIncoming = remember(updateTrigger) { preferences.isIgnoreAnonymousIncomingEnabled() }
    val ignoreCrossCountryIncoming = remember(updateTrigger) { preferences.isIgnoreCrossCountryIncomingEnabled() }
    SettingsToggleRow(
        label           = stringResource(R.string.settings_ignore_cross_country_incoming),
        checked         = ignoreCrossCountryIncoming,
        onCheckedChange = { actions.setIgnoreCrossCountryIncoming(it) },
        enabled         = ignoreAnonymousIncoming
    )
}

@Composable
internal fun IgnoreCrossCountryOutgoingRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val ignoreCrossCountryOutgoing = remember(updateTrigger) { preferences.isIgnoreCrossCountryOutgoingEnabled() }
    SettingsToggleRow(
        label           = stringResource(R.string.settings_ignore_cross_country_outgoing),
        checked         = ignoreCrossCountryOutgoing,
        onCheckedChange = { actions.setIgnoreCrossCountryOutgoing(it) }
    )
}

/** Which incoming contacts to skip, or to record only. The count belongs to the list the mode uses. */
@Composable
internal fun IgnoreContactsIncomingRow(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    onOpenContactsIncoming: () -> Unit,
) {
    val mode = remember(updateTrigger) { preferences.getIgnoreContactsModeIncoming() }
    val count = remember(updateTrigger, mode) {
        if (mode == AppPreferences.IgnoreContactsMode.ONLY_SELECTED) preferences.getRecordOnlyContactsIncoming().size
        else preferences.getIgnoredContactsIncoming().size
    }
    IgnoreContactsOptions(
        label           = stringResource(R.string.settings_ignore_contacts_incoming),
        selectedEnum     = mode,
        selectedCount    = count,
        onSelected      = { actions.setIgnoreContactsModeIncoming(it) },
        onSelectContacts = onOpenContactsIncoming
    )
}

/** Which outgoing contacts to skip, or to record only. The count belongs to the list the mode uses. */
@Composable
internal fun IgnoreContactsOutgoingRow(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    onOpenContactsOutgoing: () -> Unit,
) {
    val mode = remember(updateTrigger) { preferences.getIgnoreContactsModeOutgoing() }
    val count = remember(updateTrigger, mode) {
        if (mode == AppPreferences.IgnoreContactsMode.ONLY_SELECTED) preferences.getRecordOnlyContactsOutgoing().size
        else preferences.getIgnoredContactsOutgoing().size
    }
    IgnoreContactsOptions(
        label           = stringResource(R.string.settings_ignore_contacts_outgoing),
        selectedEnum     = mode,
        selectedCount    = count,
        onSelected      = { actions.setIgnoreContactsModeOutgoing(it) },
        onSelectContacts = onOpenContactsOutgoing
    )
}

/** The file-name template row, with the dialog that edits it. */
@Composable
internal fun FileNameTemplateRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val fileNameFormat = remember(updateTrigger) { preferences.getFileNameTemplate() }
    var showFileNameFormatDialog by remember { mutableStateOf(false) }
    NavigationRow(
        icon = Icons.Filled.DriveFileRenameOutline,
        label = stringResource(R.string.settings_file_name_template),
        value = if (CustomFileNameTemplate.isPreset(fileNameFormat)) stringResource(presetForTemplateOrFirst(fileNameFormat).labelRes)
            else stringResource(R.string.settings_file_name_preset_custom),
        supporting = stringResource(
            R.string.settings_file_name_template_example,
            fileNameTemplateExample(fileNameFormat)
        ),
        onClick = { showFileNameFormatDialog = true },
        // An example of the chosen name: a value, not an explanation.
        keepSupportingInline = true,
    )
    if (showFileNameFormatDialog) {
        FileNameFormatDialog(
            initialFormat = fileNameFormat,
            onConfirm = { format ->
                actions.setFileNameTemplate(format)
                showFileNameFormatDialog = false
            },
            onDismiss = { showFileNameFormatDialog = false }
        )
    }
}

/**
 * Opt-in: each phone call shows which SIM it used — the SIM's own name, else "SIM 1" / "SIM 2".
 * Read from the call log when the list is drawn, so it also labels calls recorded before it was turned on.
 */
@Composable
internal fun SimLabelRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SettingsToggleRow(
        label = stringResource(R.string.settings_sim_label_label),
        description = stringResource(R.string.settings_sim_label_desc),
        checked = remember(updateTrigger) { preferences.isShowSimLabelEnabled() },
        onCheckedChange = { actions.setShowSimLabelEnabled(it) },
    )
}
