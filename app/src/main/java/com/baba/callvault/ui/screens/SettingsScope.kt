/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import com.baba.callvault.data.StorageTarget
import com.baba.callvault.ui.navigation.HomeSection

/**
 * Which page Settings was opened from, and therefore which groups the new Settings screen shows.
 *
 * The classic screen ignores it and always shows everything. See
 * `docs/dev-notes/2026-10-20-settings-per-page-plan.md`.
 */
enum class SettingsScope { RECORDINGS, TRANSCRIPTS, SUMMARIES, ALL }

/**
 * One card on the new Settings screen, grouped by what the user wants to do (round 2, agreed 2026-10-21).
 * Each one wraps rows that also exist on the classic screen; no group owns a setting of its own, so both
 * screens read and write the same preferences.
 *
 * [key] is what the screen remembers as "the open group". It is new, and does not reuse the classic
 * screen's section keys, so neither screen can disturb the other's state.
 */
enum class SettingsGroup(val key: String) {
    /** "Recording": phone calls, app calls, then what applies to all calls (round 4). */
    CALLS_TO_RECORD("group_calls_to_record"),

    /** Contact lists, anonymous and other-country calls, the app-call skip switch, short calls. */
    CALLS_TO_SKIP("group_calls_to_skip"),

    /** Audio source, codec and bit rate, and the file-name template. */
    SOUND_AND_NAMES("group_sound_and_names"),

    /** Device / Drive, the folders, the upload schedule, and what lands beside each recording. */
    SAVE_AND_SYNC("group_save_and_sync"),

    /** Retention, the space limit and when it runs, and keeping originals after a merge. */
    CLEANING_UP("group_cleaning_up"),

    /** The classic Experimental ▸ Resilience rows, in the order of [ConnectionRows]. */
    CONNECTION("group_connection"),

    /** Where to transcribe (#48), speech model (with its download), the shared language, ask each time. */
    MODEL_AND_LANGUAGE("group_model_and_language"),

    /** Transcription mode, run time, calls per run, only while charging, ask before transcribing. */
    WHEN_TO_TRANSCRIBE("group_when_to_transcribe"),

    /** The summary rows, plus the shared language shown read-only (never a second picker). */
    SUMMARIES("group_summaries"),

    /** The app itself: language, look, toasts, app lock, screenshots, updates, settings backup. */
    APP("group_app"),

    /** Debug (with the classic-screen switch), then About. */
    HELP_AND_ABOUT("group_help_and_about");

    companion object {
        fun fromKey(key: String?): SettingsGroup? = entries.firstOrNull { it.key == key }
    }
}

/** The one table that says which groups each scope shows, in what order, and what each group holds. */
object SettingsGroups {

    private val RECORDINGS = listOf(
        SettingsGroup.CALLS_TO_RECORD,
        SettingsGroup.CALLS_TO_SKIP,
        SettingsGroup.SOUND_AND_NAMES,
        SettingsGroup.SAVE_AND_SYNC,
        SettingsGroup.CLEANING_UP,
        SettingsGroup.CONNECTION,
    )

    private val TRANSCRIPTS = listOf(SettingsGroup.MODEL_AND_LANGUAGE, SettingsGroup.WHEN_TO_TRANSCRIBE)

    private val SUMMARIES = listOf(SettingsGroup.SUMMARIES)

    /** Everything the classic screen offers, nothing lost: each page's groups, then the app-wide ones. */
    private val ALL = RECORDINGS + TRANSCRIPTS + SUMMARIES + listOf(
        SettingsGroup.APP,
        SettingsGroup.HELP_AND_ABOUT,
    )

    fun forScope(scope: SettingsScope): List<SettingsGroup> = when (scope) {
        SettingsScope.RECORDINGS -> RECORDINGS
        SettingsScope.TRANSCRIPTS -> TRANSCRIPTS
        SettingsScope.SUMMARIES -> SUMMARIES
        SettingsScope.ALL -> ALL
    }

    /** The scope Settings opens in from each page's settings icon. The main screen shows everything. */
    fun scopeFor(section: HomeSection): SettingsScope = when (section) {
        HomeSection.Hub -> SettingsScope.ALL
        HomeSection.Recordings -> SettingsScope.RECORDINGS
        HomeSection.Transcripts -> SettingsScope.TRANSCRIPTS
        HomeSection.Summaries -> SettingsScope.SUMMARIES
    }

    /**
     * The row composables each group's body calls, by name. `SettingsGroupsTest` checks every row the classic
     * screen calls is in some group of ALL, and `ScopedSettingsScreenTest` checks each body really calls these.
     */
    fun rowsOf(group: SettingsGroup): List<String> = when (group) {
        SettingsGroup.CALLS_TO_RECORD -> listOf(
            "CarrierRecordingRow", "VoipRecordingSwitch", "VoipAppPicker",
            "AutoRecordIncomingRow", "AutoRecordOutgoingRow", "RecordFromAnswerRow", "VoipAutoStartRow",
            "VibrationRow",
        )
        SettingsGroup.CALLS_TO_SKIP -> listOf(
            "IgnoreContactsIncomingRow", "IgnoreContactsOutgoingRow", "IgnoreAnonymousIncomingRow",
            "IgnoreCrossCountryIncomingRow", "IgnoreCrossCountryOutgoingRow", "VoipIgnoreContactsRow",
            "MinDurationRow",
        )
        SettingsGroup.SOUND_AND_NAMES -> listOf("AudioSection", "FileNameTemplateRow")
        SettingsGroup.SAVE_AND_SYNC -> listOf(
            "StorageTargetRow", "RecordingFolderRow", "DriveFolderRow", "UploadScheduleSubSection",
            "HideRecordingsRow", "MetadataFileRow", "TranscriptSidecarRow",
        )
        SettingsGroup.CLEANING_UP -> listOf("RetentionPolicyRows", "MergeSubSection")
        SettingsGroup.CONNECTION -> listOf("ConnectionRows")
        SettingsGroup.MODEL_AND_LANGUAGE -> listOf(
            "TranscriptionServiceRows", "TranscriptionModelChoiceRow", "TranscriptionModelDownloadRows",
            "TranscriptionLanguageRows",
        )
        SettingsGroup.WHEN_TO_TRANSCRIBE -> listOf(
            "TranscriptionScheduleRows", "TranscriptionConfirmRow", "TranscriptionModeNote",
        )
        SettingsGroup.SUMMARIES -> listOf("SummarySettingsRows")
        SettingsGroup.APP -> listOf("AppearanceRows", "PrivacySubSection", "UpdatesSubSection", "ExportImportRow")
        SettingsGroup.HELP_AND_ABOUT -> listOf("BugReportSection", "AboutSection")
    }

    /**
     * The preference keys (and gated-switch ids) each group's rows read and write. `SettingsGroupsTest` checks
     * every exportable setting and every gated switch has a home in ALL.
     */
    fun settingKeysOf(group: SettingsGroup): List<String> = when (group) {
        SettingsGroup.CALLS_TO_RECORD -> listOf(
            "carrier_recording_enabled", "voip_recording_enabled", "voip_recording", "voip_excluded_packages",
            "auto_record_incoming", "auto_record_outgoing", "record_from_answer", "voip_auto_start",
            "vibration_enabled",
        )
        SettingsGroup.CALLS_TO_SKIP -> listOf(
            "ignore_contacts_mode_incoming", "ignore_contacts_mode_outgoing",
            "ignored_contacts_incoming", "ignored_contacts_outgoing",
            "record_only_contacts_incoming", "record_only_contacts_outgoing",
            "ignore_anonymous_incoming", "ignore_cross_country_incoming", "ignore_cross_country_outgoing",
            "voip_apply_ignored_contacts", "min_duration_seconds",
        )
        SettingsGroup.SOUND_AND_NAMES -> listOf("audio_source", "audio_codec", "audio_bitrate", "file_name_template")
        SettingsGroup.SAVE_AND_SYNC -> listOf(
            "storage_target", "recording_folder_uri", "drive_folder_uri",
            "sync_schedule_mode", "sync_time_hour", "sync_time_minute", "sync_day_of_week",
            "hide_recordings", "write_metadata_file", "write_transcript_sidecar",
        )
        SettingsGroup.CLEANING_UP -> listOf(
            "retention_linked", "retention_local_days", "retention_drive_days",
            "retention_time_hour", "retention_time_minute", "storage_cap_bytes", "keep_originals_after_merge",
        )
        SettingsGroup.CONNECTION -> listOf("handoff_persist", "offline_recording", "wd_enforced", "wd_auto_approve_network")
        SettingsGroup.MODEL_AND_LANGUAGE -> listOf(
            "transcription_service", "transcription_cloud_mobile_data", "transcription_custom_base_url",
            "transcription_custom_model", "transcription_model_id", "transcription_language",
            "transcription_ask_language",
        )
        SettingsGroup.WHEN_TO_TRANSCRIBE -> listOf(
            "transcription_mode", "transcription_hour", "transcription_minute",
            "transcription_requires_charging", "transcription_batch_limit", "transcription_confirm_before_run",
        )
        SettingsGroup.SUMMARIES -> listOf("summary_confirm_requirements")
        SettingsGroup.APP -> listOf("theme_mode", "dynamic_color", "show_toasts", "update_check_enabled")
        SettingsGroup.HELP_AND_ABOUT -> listOf("logging_enabled")
    }
}

/** The rows of "Save and sync", in screen order. */
enum class SaveAndSyncRow {
    STORAGE_TARGET, RECORDING_FOLDER, DRIVE_FOLDER, UPLOAD_SCHEDULE, HIDE_RECORDINGS, METADATA_FILE, TRANSCRIPT_SIDECAR,
}

/**
 * Which "Save and sync" rows show for a storage target. The recording folder always does: even Drive-only
 * records into it first and deletes the local copy after the upload. The Drive folder and the upload schedule
 * only mean something when something is uploaded, so "On this device only" hides them — showing the schedule
 * under Drive is what makes the two read as one thing (round 3, 2026-10-21).
 */
object SaveAndSyncRows {
    fun uploads(target: StorageTarget): Boolean = target != StorageTarget.LOCAL

    fun visibleRows(target: StorageTarget): List<SaveAndSyncRow> = SaveAndSyncRow.entries.filter { row ->
        when (row) {
            SaveAndSyncRow.DRIVE_FOLDER, SaveAndSyncRow.UPLOAD_SCHEDULE -> uploads(target)
            else -> true
        }
    }
}
