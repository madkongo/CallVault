/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.ModeCapability
import com.baba.callvault.data.RetentionPeriod
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.ui.common.SyncScheduleLabels
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.ui.common.TranscriptionLabels
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.transcription.cloud.TranscriptionService

/**
 * The one-line summary under a group card's title, read from the same preferences as the group's rows.
 *
 * Re-read when [updateTrigger] moves and whenever the card opens or closes: several rows (app calls,
 * reliability) save straight to preferences without bumping the trigger, and closing the card is the
 * moment the user looks back at its summary.
 */
@Composable
internal fun groupSummaryText(
    group: SettingsGroup,
    preferences: AppPreferences,
    updateTrigger: Int,
    expanded: Boolean,
    actions: SettingsActions,
): String = when (group) {
    SettingsGroup.CALLS_TO_RECORD -> callsToRecordSummaryText(preferences, updateTrigger, expanded)
    SettingsGroup.CALLS_TO_SKIP -> callsToSkipSummaryText(preferences, updateTrigger, expanded)
    SettingsGroup.SOUND_AND_NAMES -> {
        val codecKey = remember(updateTrigger) { preferences.getAudioCodec() }
        val bitRate = remember(updateTrigger) { preferences.getAudioBitRate() }
        val codec = ScrcpyAudioCodec.entries.firstOrNull { it.cliKey == codecKey }
        joinSummary(
            listOf(
                codec?.let { stringResource(it.titleResId) },
                stringResource(R.string.audio_bitrate_kbps, bitRate / BITS_PER_KILOBIT),
            )
        )
    }
    SettingsGroup.SAVE_AND_SYNC -> saveAndSyncSummaryText(preferences, updateTrigger)
    SettingsGroup.CLEANING_UP -> {
        val localDays = remember(updateTrigger) { preferences.getRetentionLocalDays() }
        val capBytes = remember(updateTrigger) { preferences.getStorageCapBytes() }
        joinSummary(
            listOf(
                stringResource(RetentionPeriod.fromDays(localDays).labelRes),
                if (capBytes > 0L) stringResource(R.string.storage_cap_gigabytes, capBytes / BYTES_PER_GIGABYTE) else null,
            )
        )
    }
    SettingsGroup.CONNECTION -> connectionSummaryText(preferences, updateTrigger, expanded)
    SettingsGroup.MODEL_AND_LANGUAGE -> {
        val modelId = remember(updateTrigger) { preferences.getTranscriptionModelId() }
        val model = TranscriptionModel.fromId(modelId) ?: TranscriptionModel.DEFAULT
        val service = remember(updateTrigger) { preferences.getTranscriptionService() }
        joinSummary(
            listOf(
                // A cloud service (#48) names itself; on this phone, the model does.
                when (service) {
                    TranscriptionService.ON_DEVICE -> stringResource(TranscriptionLabels.titleOf(model))
                    TranscriptionService.CUSTOM -> stringResource(R.string.transcription_where_custom)
                    else -> service.preset?.displayName.orEmpty()
                },
                sharedLanguageLabel(preferences, updateTrigger),
            )
        )
    }
    SettingsGroup.WHEN_TO_TRANSCRIBE -> {
        val mode = remember(updateTrigger) { preferences.getTranscriptionMode() }
        stringResource(TranscriptionLabels.titleOf(mode))
    }
    SettingsGroup.SUMMARIES -> {
        val context = LocalContext.current
        val installed = remember(updateTrigger, expanded) {
            ModelRepository.isInstalled(context, SummaryModel.DEFAULT)
        }
        joinSummary(
            listOf(
                stringResource(if (installed) R.string.summary_model_installed else R.string.settings_summary_not_downloaded),
                sharedLanguageLabel(preferences, updateTrigger),
            )
        )
    }
    SettingsGroup.APP -> {
        val theme = remember(updateTrigger) { preferences.getThemeMode() }
        stringResource(
            when (theme) {
                AppPreferences.ThemeMode.SYSTEM -> R.string.settings_theme_mode_system
                AppPreferences.ThemeMode.LIGHT -> R.string.settings_theme_mode_light
                AppPreferences.ThemeMode.DARK -> R.string.settings_theme_mode_dark
            }
        )
    }
    SettingsGroup.HELP_AND_ABOUT -> actions.getAppVersion()
}

/** The storage cap is stored in bytes and offered in whole gigabytes (GiB), as in the Retention dropdown. */
private const val BYTES_PER_GIGABYTE = 1L shl 30

/** "On this device only", or the target and the upload schedule: "Device + Google Drive · Daily · 02:00". */
@Composable
private fun saveAndSyncSummaryText(preferences: AppPreferences, updateTrigger: Int): String {
    val target = remember(updateTrigger) { preferences.getStorageTarget() }
    val targetText = stringResource(
        when (target) {
            StorageTarget.LOCAL -> R.string.storage_target_local
            StorageTarget.DRIVE -> R.string.storage_target_drive
            StorageTarget.BOTH -> R.string.storage_target_both
        }
    )
    if (!SaveAndSyncRows.uploads(target)) return targetText
    val mode = remember(updateTrigger) { preferences.getSyncScheduleMode() }
    val hour = remember(updateTrigger) { preferences.getSyncTimeHour() }
    val minute = remember(updateTrigger) { preferences.getSyncTimeMinute() }
    val day = remember(updateTrigger) { preferences.getSyncDayOfWeek() }
    return joinSummary(
        listOf(targetText, stringResource(SyncScheduleLabels.titleOf(mode))) + when (mode) {
            SyncScheduleMode.IMMEDIATE -> emptyList()
            SyncScheduleMode.DAILY -> listOf(clockTime(hour, minute))
            SyncScheduleMode.WEEKLY -> listOf(stringResource(SyncScheduleLabels.dayOfWeekOf(day)) + " " + clockTime(hour, minute))
        }
    )
}

/** kbps from a stored bit rate in bits per second. */
private const val BITS_PER_KILOBIT = 1000

/** The one language transcripts and summaries share, as the user sees it in the picker. */
@Composable
internal fun sharedLanguageLabel(preferences: AppPreferences, updateTrigger: Int): String {
    val language = remember(updateTrigger) { preferences.getTranscriptionLanguage() }
    return stringResource(TranscriptionLabels.languageOf(language))
}

@Composable
private fun directionText(direction: RecordingSummary): String? = when (direction) {
    RecordingSummary.OFF -> null
    RecordingSummary.BOTH -> stringResource(R.string.settings_summary_recording_both)
    RecordingSummary.INCOMING_ONLY -> stringResource(R.string.settings_summary_recording_incoming)
    RecordingSummary.OUTGOING_ONLY -> stringResource(R.string.settings_summary_recording_outgoing)
    RecordingSummary.MANUAL_ONLY -> stringResource(R.string.settings_summary_recording_manual)
}

/** "Phone and app calls · incoming and outgoing", "Phone calls only · …", "App calls only", or "Off". */
@Composable
private fun callsToRecordSummaryText(preferences: AppPreferences, updateTrigger: Int, expanded: Boolean): String {
    val appSupported = capabilityAvailable(ModeCapability.VOIP_RECORDING)
    val summary = remember(updateTrigger, expanded, appSupported) {
        callsToRecordSummary(
            isPhoneOn = preferences.isCarrierRecordingEnabled(),
            isAppOn = appSupported && preferences.isVoipRecordingEnabled(),
            isIncomingOn = preferences.isAutoRecordIncomingEnabled(),
            isOutgoingOn = preferences.isAutoRecordOutgoingEnabled(),
        )
    }
    val which = when (summary.which) {
        CallsWhich.NONE -> return stringResource(R.string.settings_summary_off)
        CallsWhich.PHONE_AND_APP -> R.string.settings_summary_calls_phone_and_app
        CallsWhich.PHONE_ONLY -> R.string.settings_summary_calls_phone_only
        CallsWhich.APP_ONLY -> R.string.settings_summary_calls_app_only
    }
    return joinSummary(listOf(stringResource(which), summary.direction?.let { directionText(it) }))
}

/**
 * How many skip rules are on. A rule counts only where its row can act: other-country incoming is greyed out
 * unless anonymous incoming is skipped, and the app-call rule needs app calls on.
 */
@Composable
private fun callsToSkipSummaryText(preferences: AppPreferences, updateTrigger: Int, expanded: Boolean): String {
    val count = remember(updateTrigger, expanded) {
        val ignoreAnonymous = preferences.isIgnoreAnonymousIncomingEnabled()
        activeRuleCount(
            listOf(
                preferences.getIgnoreContactsModeIncoming() != AppPreferences.IgnoreContactsMode.NONE,
                preferences.getIgnoreContactsModeOutgoing() != AppPreferences.IgnoreContactsMode.NONE,
                ignoreAnonymous,
                ignoreAnonymous && preferences.isIgnoreCrossCountryIncomingEnabled(),
                preferences.isIgnoreCrossCountryOutgoingEnabled(),
                preferences.isVoipRecordingEnabled() && preferences.isVoipApplyIgnoredContactsEnabled(),
                preferences.getMinDurationSeconds() > 0,
            )
        )
    }
    if (count == 0) return stringResource(R.string.settings_summary_nothing_skipped)
    return stringResource(R.string.settings_summary_skip_rules_on, count)
}

/**
 * "N of M on" over the Connection switches, counting only the ones usable in this mode, as their rows
 * grey out the rest. The mode is read live (never remembered) so the line follows a mode switch.
 */
@Composable
private fun connectionSummaryText(preferences: AppPreferences, updateTrigger: Int, expanded: Boolean): String {
    val context = LocalContext.current
    val usesEmbeddedAdb = !currentPrivilegedMode().needsShizuku
    val handoffSupported = capabilityAvailable(ModeCapability.RESILIENT_RECORDING)
    val offlineSupported = capabilityAvailable(ModeCapability.OFFLINE_RECORDING)
    val wdControlSupported = capabilityAvailable(ModeCapability.WIRELESS_DEBUGGING_CONTROL)
    val count = remember(updateTrigger, expanded, usesEmbeddedAdb, handoffSupported, offlineSupported, wdControlSupported) {
        countSummary(
            listOf(
                SwitchSummaryInput(preferences.isHandoffPersistEnabled(), handoffSupported),
                SwitchSummaryInput(preferences.isOfflineRecordingEnabled(), offlineSupported),
                SwitchSummaryInput(AdbShell.isUsbDebuggingEnabled(context), usesEmbeddedAdb),
                SwitchSummaryInput(preferences.isWirelessDebuggingEnforced(), usesEmbeddedAdb),
                SwitchSummaryInput(preferences.isWifiNetworkAutoApproveEnabled(), wdControlSupported),
            )
        )
    }
    if (count.total == 0) return stringResource(R.string.settings_summary_unavailable)
    return stringResource(R.string.settings_summary_count_on, count.on, count.total)
}

/**
 * The explanation behind a group card's ⓘ: text that belongs to the whole group rather than one row, which
 * the classic screen prints as a paragraph and the new screen hides ([ExplanationParagraph]). Null for groups
 * with none.
 */
@Composable
internal fun groupExplanationText(group: SettingsGroup, preferences: AppPreferences, updateTrigger: Int): String? =
    when (group) {
        SettingsGroup.CLEANING_UP -> stringResource(R.string.retention_description)
        SettingsGroup.WHEN_TO_TRANSCRIBE -> {
            val mode = remember(updateTrigger) { preferences.getTranscriptionMode() }
            stringResource(transcriptionModeNoteRes(mode))
        }
        SettingsGroup.SUMMARIES -> summaryModelExplanation()
        SettingsGroup.HELP_AND_ABOUT -> stringResource(R.string.settings_bugreport_share_hint)
        else -> null
    }
