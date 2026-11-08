/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.viewmodels.SettingsActions

/**
 * The callbacks a group body may need, so the dispatcher below stays readable.
 */
internal class GroupBodyCallbacks(
    val onSelectFolder: () -> Unit,
    val onSelectDriveFolder: () -> Unit,
    val onOpenContactsIncoming: () -> Unit,
    val onOpenContactsOutgoing: () -> Unit,
    val onShareLogs: () -> Unit,
    val onSaveLogs: () -> Unit,
    val onExportSettings: () -> Unit,
    val onImportSettings: () -> Unit,
    val onShowLicenses: () -> Unit,
    val onChangeLanguage: () -> Unit,
)

/**
 * A group's rows. Every row is one the classic screen also draws (see [SettingsGroups.rowsOf]); a classic
 * section wrapped whole draws only its rows here ([LocalSettingsSectionInGroupCard]), so the `expanded` and
 * `onToggle` passed to it are placeholders.
 */
@Composable
internal fun GroupBody(
    group: SettingsGroup,
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    callbacks: GroupBodyCallbacks,
) {
    when (group) {
        SettingsGroup.CALLS_TO_RECORD -> CallsToRecordBody(preferences, updateTrigger, actions)
        SettingsGroup.CALLS_TO_SKIP -> CallsToSkipBody(preferences, updateTrigger, actions, callbacks)
        SettingsGroup.SOUND_AND_NAMES -> SoundAndNamesBody(preferences, updateTrigger, actions)
        SettingsGroup.SAVE_AND_SYNC -> SaveAndSyncBody(preferences, updateTrigger, actions, callbacks)
        SettingsGroup.CLEANING_UP -> CleaningUpBody(preferences, updateTrigger, actions)
        SettingsGroup.CONNECTION -> ConnectionBody()
        SettingsGroup.MODEL_AND_LANGUAGE -> ModelAndLanguageBody(preferences, updateTrigger, actions)
        SettingsGroup.WHEN_TO_TRANSCRIBE -> WhenToTranscribeBody(preferences, updateTrigger, actions)
        SettingsGroup.SUMMARIES -> SummariesBody(preferences, updateTrigger, actions, callbacks)
        SettingsGroup.APP -> AppBody(preferences, updateTrigger, actions, callbacks)
        SettingsGroup.HELP_AND_ABOUT -> HelpAndAboutBody(preferences, updateTrigger, actions, callbacks)
    }
}

/**
 * "Recording": phone-call settings, app-call settings, then the ones that apply to both, each under its own
 * sub-header so the app calls' automatic switch cannot be read as the phone calls' (round 4, 2026-10-21).
 * Phone-only rows follow the phone switch and app-only rows the app switch, as on the classic screen.
 */
@Composable
private fun CallsToRecordBody(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val isPhoneOn = remember(updateTrigger) { preferences.isCarrierRecordingEnabled() }
    // Held here so the app picker and the automatic switch follow the app-call switch at once.
    var isAppOn by remember { mutableStateOf(prefs.isVoipRecordingEnabled()) }
    var isAskingAfterCall by remember { mutableStateOf(prefs.getAfterCallMode() == AppPreferences.AfterCallMode.ASK) }
    val switches = SettingsFolds.Switches(isPhoneOn, isAppOn, isAskingAfterCall)

    SettingsSubHeader(stringResource(R.string.settings_subsection_phone_calls))
    SearchFiltered(SettingsFolds.PHONE_SWITCH, unfolds = SettingsFolds.foldedBy(SettingsFolds.PHONE_SWITCH, switches)) { CarrierRecordingRow(preferences, updateTrigger, actions) }
    ShownWhen(isPhoneOn) {
        Column {
            SearchFiltered("AutoRecordIncomingRow") { AutoRecordIncomingRow(preferences, updateTrigger, actions) }
            SearchFiltered("AutoRecordOutgoingRow") { AutoRecordOutgoingRow(preferences, updateTrigger, actions) }
        }
    }

    SettingsSubHeader(stringResource(R.string.settings_subheader_app_calls))
    SearchFiltered(SettingsFolds.APP_SWITCH, unfolds = SettingsFolds.foldedBy(SettingsFolds.APP_SWITCH, switches)) { VoipRecordingSwitch(enabled = isAppOn, onEnabledChange = { isAppOn = it }) }
    ShownWhen(isAppOn) {
        Column {
            SearchFiltered("VoipAppPicker") { VoipAppPicker(prefs) }
            SearchFiltered("VoipAutoStartRow") { VoipAutoStartRow(label = stringResource(R.string.settings_voip_auto_start_record_label)) }
        }
    }

    SettingsSubHeader(stringResource(R.string.settings_subheader_all_calls))
    // Covers app calls too (when the app shows a call timer), so under "All calls" it shows while either
    // kind of call is recorded, not only phone calls as it did under "When to start".
    ShownWhen(isPhoneOn || isAppOn) { SearchFiltered("RecordFromAnswerRow") { RecordFromAnswerRow(preferences, updateTrigger, actions) } }
    SearchFiltered("VibrationRow") { VibrationRow(preferences, updateTrigger, actions) }
    // Keep or delete after the call (2026-11-06): for phone and app calls alike, so under "All calls".
    SearchFiltered(SettingsFolds.ASK_SWITCH, unfolds = SettingsFolds.foldedBy(SettingsFolds.ASK_SWITCH, switches)) { AfterCallModeRow(onAskChange = { isAskingAfterCall = it }) }
    ShownWhen(isAskingAfterCall) {
        Column {
            SearchFiltered("AfterCallAskStyleRow") { AfterCallAskStyleRow() }
            SearchFiltered("AfterCallUnansweredRow") { AfterCallUnansweredRow() }
        }
    }
}

/** Every rule that keeps a call from being recorded, phone and app calls alike, and the short-call cut-off. */
@Composable
private fun CallsToSkipBody(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    callbacks: GroupBodyCallbacks,
) {
    val context = LocalContext.current
    val isAppOn = remember(updateTrigger) { AppPreferences(context).isVoipRecordingEnabled() }

    SearchFiltered("IgnoreContactsIncomingRow") { IgnoreContactsIncomingRow(preferences, updateTrigger, actions, callbacks.onOpenContactsIncoming) }
    SearchFiltered("IgnoreContactsOutgoingRow") { IgnoreContactsOutgoingRow(preferences, updateTrigger, actions, callbacks.onOpenContactsOutgoing) }
    SettingsDivider()
    SearchFiltered("IgnoreAnonymousIncomingRow") { IgnoreAnonymousIncomingRow(preferences, updateTrigger, actions) }
    SearchFiltered("IgnoreCrossCountryIncomingRow") { IgnoreCrossCountryIncomingRow(preferences, updateTrigger, actions) }
    SearchFiltered("IgnoreCrossCountryOutgoingRow") { IgnoreCrossCountryOutgoingRow(preferences, updateTrigger, actions) }
    // Only means something while app calls are recorded, as under the VoIP switch on the classic screen.
    if (isAppOn) SearchFiltered("VoipIgnoreContactsRow") { VoipIgnoreContactsRow() }
    SettingsDivider()
    SearchFiltered("MinDurationRow") { MinDurationRow(preferences, updateTrigger, actions) }
}

@Composable
private fun SoundAndNamesBody(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SearchFiltered("AudioSection") { AudioSection(preferences, updateTrigger, actions, expanded = true, onToggle = {}) }
    SettingsDivider()
    SearchFiltered("FileNameTemplateRow") { FileNameTemplateRow(preferences, updateTrigger, actions) }
    SettingsDivider()
    SearchFiltered("SimLabelRow") { SimLabelRow(preferences, updateTrigger, actions) }
}

@Composable
private fun SaveAndSyncBody(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    callbacks: GroupBodyCallbacks,
) {
    val storageTarget = remember(updateTrigger) { preferences.getStorageTarget() }
    SaveAndSyncRows.visibleRows(storageTarget).forEach { row ->
        when (row) {
            SaveAndSyncRow.STORAGE_TARGET -> SearchFiltered("StorageTargetRow") { StorageTargetRow(preferences, updateTrigger, actions) }
            SaveAndSyncRow.RECORDING_FOLDER -> SearchFiltered("RecordingFolderRow") { RecordingFolderRow(preferences, updateTrigger, callbacks.onSelectFolder) }
            SaveAndSyncRow.DRIVE_FOLDER -> SearchFiltered("DriveFolderRow") { DriveFolderRow(preferences, updateTrigger, callbacks.onSelectDriveFolder) }
            SaveAndSyncRow.UPLOAD_SCHEDULE -> {
                SettingsSubHeader(stringResource(R.string.wizard_schedule_title))
                SearchFiltered("UploadScheduleSubSection") { UploadScheduleSubSection(preferences, updateTrigger, actions) }
            }
            SaveAndSyncRow.HIDE_RECORDINGS -> {
                SettingsDivider()
                SearchFiltered("HideRecordingsRow") { HideRecordingsRow(preferences, updateTrigger, actions) }
            }
            SaveAndSyncRow.METADATA_FILE -> SearchFiltered("MetadataFileRow") { MetadataFileRow(preferences, updateTrigger, actions) }
            SaveAndSyncRow.TRANSCRIPT_SIDECAR -> SearchFiltered("TranscriptSidecarRow") { TranscriptSidecarRow(preferences, updateTrigger, actions) }
        }
    }
}

@Composable
private fun CleaningUpBody(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SearchFiltered("RetentionPolicyRows") { RetentionPolicyRows(preferences, updateTrigger, actions) }
    SettingsDivider()
    SearchFiltered("MergeSubSection") { MergeSubSection(preferences, updateTrigger) }
}

@Composable
private fun ConnectionBody() {
    SearchFiltered("ConnectionRows") { ConnectionRows() }
}

@Composable
private fun ModelAndLanguageBody(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SearchFiltered("TranscriptionServiceRows") { TranscriptionServiceRows(preferences, updateTrigger, actions) }
    // The speech model only matters on this phone; a cloud service (#48) brings its own.
    val onDevice = remember(updateTrigger) { !preferences.getTranscriptionService().isCloud }
    if (onDevice) {
        SettingsDivider()
        SearchFiltered("TranscriptionModelChoiceRow") { TranscriptionModelChoiceRow(preferences, updateTrigger, actions) }
        SearchFiltered("TranscriptionModelDownloadRows") { TranscriptionModelDownloadRows(preferences, updateTrigger, actions) }
    }
    SettingsDivider()
    SearchFiltered("TranscriptionLanguageRows") { TranscriptionLanguageRows(preferences, updateTrigger, actions) }
}

@Composable
private fun WhenToTranscribeBody(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SearchFiltered("TranscriptionScheduleRows") { TranscriptionScheduleRows(preferences, updateTrigger, actions) }
    SearchFiltered("TranscriptionConfirmRow") { TranscriptionConfirmRow(preferences, updateTrigger, actions) }
    SearchFiltered("TranscriptionModeNote") { TranscriptionModeNote(preferences, updateTrigger) }
}

/** Transcripts and summaries share ONE language on purpose; here it is read-only, with a link to it. */
@Composable
private fun SummariesBody(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    callbacks: GroupBodyCallbacks,
) {
    NavigationRow(
        icon = Icons.Filled.Translate,
        label = stringResource(R.string.transcription_language_label),
        value = sharedLanguageLabel(preferences, updateTrigger),
        supporting = stringResource(R.string.settings_language_change_in_transcripts),
        onClick = callbacks.onChangeLanguage,
        keepSupportingInline = true,
    )
    SettingsDivider()
    SearchFiltered("SummarySettingsRows") { SummarySettingsRows(preferences, updateTrigger, actions) }
}

@Composable
private fun AppBody(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    callbacks: GroupBodyCallbacks,
) {
    SearchFiltered("AppearanceRows") { AppearanceRows(preferences, updateTrigger, actions) }
    SettingsSubHeader(stringResource(R.string.settings_section_privacy))
    SearchFiltered("PrivacySubSection") { PrivacySubSection(preferences, updateTrigger) }
    SettingsSubHeader(stringResource(R.string.settings_section_updates))
    SearchFiltered("UpdatesSubSection") { UpdatesSubSection(preferences, updateTrigger, actions) }
    SettingsDivider()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SearchFiltered("ExportImportRow") { ExportImportRow(onExport = callbacks.onExportSettings, onImport = callbacks.onImportSettings) }
    }
}

/** Debug first (what support asks for), then About. */
@Composable
private fun HelpAndAboutBody(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    callbacks: GroupBodyCallbacks,
) {
    SearchFiltered("BugReportSection") {
        BugReportSection(
            preferences, updateTrigger, actions, callbacks.onShareLogs, callbacks.onSaveLogs,
            expanded = true, onToggle = {},
        )
    }
    SettingsSubHeader(stringResource(R.string.settings_section_about))
    SearchFiltered("AboutSection") {
        AboutSection(
            versionString = actions.getAppVersion(),
            onShowLicenses = callbacks.onShowLicenses,
            expanded = true,
            onToggle = {},
        )
    }
}

/** Rows that only apply while a switch is on fold in and out with it, as on the classic screen. */
@Composable
private fun ShownWhen(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) { content() }
}
