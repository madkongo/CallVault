/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.baba.callvault.R
import kotlinx.coroutines.flow.first
import androidx.annotation.StringRes
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.RetentionPeriod
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.ui.common.SyncScheduleLabels
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.system.storage.FolderOverlap
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.viewmodels.SettingsActions
import androidx.compose.material.icons.filled.Folder
import com.baba.callvault.system.storage.MinDurationPolicy
import com.baba.callvault.system.storage.StorageCapPolicy

/** Where recordings are saved: this device, Drive, or both. */
@Composable
internal fun StorageTargetRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val storageTarget = remember(updateTrigger) { preferences.getStorageTarget() }
    val storageTargetOptions = StorageTarget.entries.map { target ->
        val labelRes = when (target) {
            StorageTarget.LOCAL -> R.string.storage_target_local
            StorageTarget.DRIVE -> R.string.storage_target_drive
            StorageTarget.BOTH  -> R.string.storage_target_both
        }
        OptionItem(target.key, stringResource(labelRes))
    }
    DropdownRow {
        M3DropdownField(
            label    = stringResource(R.string.settings_storage_target_label),
            selected = storageTargetOptions.find { it.key == storageTarget.key } ?: storageTargetOptions.first(),
            options  = storageTargetOptions,
            onOptionSelected = { actions.setStorageTarget(StorageTarget.fromKey(it.key)) }
        )
    }
}

/** The BCR-compatible details (.json) file beside each recording. */
@Composable
internal fun MetadataFileRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SettingsToggleRow(
        label = stringResource(R.string.settings_metadata_file_label),
        description = stringResource(R.string.settings_metadata_file_desc),
        checked = remember(updateTrigger) { preferences.isWriteMetadataFileEnabled() },
        onCheckedChange = { actions.setWriteMetadataFileEnabled(it) },
        infoExtra = stringResource(R.string.settings_metadata_file_hint),
    )
    SettingsExplanation(stringResource(R.string.settings_metadata_file_hint))
}

/** A text copy of the transcript and notes beside each recording, for PC backup. */
@Composable
internal fun TranscriptSidecarRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SettingsToggleRow(
        label = stringResource(R.string.settings_transcript_sidecar_label),
        description = stringResource(R.string.settings_transcript_sidecar_desc),
        checked = remember(updateTrigger) { preferences.isWriteTranscriptSidecarEnabled() },
        onCheckedChange = { actions.setWriteTranscriptSidecarEnabled(it) },
        infoExtra = stringResource(R.string.settings_transcript_sidecar_hint),
    )
    SettingsExplanation(stringResource(R.string.settings_transcript_sidecar_hint))
}

/** What other apps get to see of the recordings folder. */
@Composable
internal fun HideRecordingsRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    SettingsToggleRow(
        label = stringResource(R.string.settings_hide_recordings_label),
        description = stringResource(R.string.settings_hide_recordings_desc),
        checked = remember(updateTrigger) { preferences.isHideRecordingsEnabled() },
        onCheckedChange = { actions.setHideRecordingsEnabled(it) },
        infoExtra = stringResource(R.string.settings_hide_recordings_hint),
    )
    SettingsExplanation(stringResource(R.string.settings_hide_recordings_hint))
}

@Composable
internal fun RecordingFolderRow(preferences: AppPreferences, updateTrigger: Int, onSelectFolder: () -> Unit) {
    val context = LocalContext.current
    val recordingFolderLabel = remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, preferences.getRecordingFolderUri()) }
    val recordingFolderIsCloud = remember(updateTrigger) { SafHelper.isCloudFolder(preferences.getRecordingFolderUri()) }
    NavigationRow(
        icon = Icons.Filled.Folder,
        label = stringResource(R.string.settings_recording_folder_label),
        value = recordingFolderLabel ?: stringResource(R.string.settings_tap_to_select_folder),
        // Surface a cloud folder (e.g. Google Drive) that was set before we started rejecting them —
        // otherwise it's indistinguishable from a local folder by name alone.
        supporting = if (recordingFolderIsCloud) stringResource(R.string.folder_cloud_warning) else null,
        onClick = onSelectFolder,
        // A warning, which stays on the row.
        keepSupportingInline = true,
    )
}

@Composable
internal fun DriveFolderRow(preferences: AppPreferences, updateTrigger: Int, onSelectDriveFolder: () -> Unit) {
    val context = LocalContext.current
    val driveFolderLabel = remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, preferences.getDriveFolderUri()) }
    // Set up before 2.5.0 refused it: nothing is copied or deleted while it overlaps, so say why.
    val overlapsRecordings = remember(updateTrigger) {
        FolderOverlap.overlaps(preferences.getRecordingFolderUri(), preferences.getDriveFolderUri())
    }
    NavigationRow(
        icon = Icons.Filled.Cloud,
        label = stringResource(R.string.settings_drive_folder_label),
        value = driveFolderLabel ?: stringResource(R.string.general_not_set),
        supporting = if (overlapsRecordings) stringResource(R.string.folder_drive_overlap_warning)
            else stringResource(R.string.settings_drive_folder_desc),
        onClick = onSelectDriveFolder,
        keepSupportingInline = overlapsRecordings,
    )
}

/** The one thing merging asks of the user: whether to keep the calls it was made from. */
@Composable
internal fun MergeSubSection(preferences: AppPreferences, updateTrigger: Int) {
    var keep by remember(updateTrigger) { mutableStateOf(preferences.isKeepOriginalsAfterMerge()) }
    SettingsToggleRow(
        label = stringResource(R.string.settings_merge_keep_originals_title),
        checked = keep,
        onCheckedChange = {
            keep = it
            preferences.setKeepOriginalsAfterMerge(it)
        },
        description = stringResource(R.string.settings_merge_keep_originals_description),
        descriptionBelow = true,
    )
}

/**
 * When finished recordings are uploaded to the Drive folder.
 *
 * The choice already drove `StorageRouter.route` and [SyncScheduler] from the day it shipped, but the
 * picker existed ONLY in the setup wizard — and the wizard cannot be re-run, so whatever was chosen
 * during onboarding (defaulting to "immediately") was permanent. Issue #20 is a user who went looking
 * for it and reasonably concluded it did not exist.
 *
 * Deferring batches the Drive app's "upload finished" notifications into one run per day or week
 * instead of one per call, which is what that reporter actually wanted; it does not silence them.
 */
@Composable
internal fun UploadScheduleSubSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions
) {
    val mode = remember(updateTrigger) { preferences.getSyncScheduleMode() }
    val hour = remember(updateTrigger) { preferences.getSyncTimeHour() }
    val minute = remember(updateTrigger) { preferences.getSyncTimeMinute() }
    val dayOfWeek = remember(updateTrigger) { preferences.getSyncDayOfWeek() }

    val modeOptions = SyncScheduleMode.entries.map {
        OptionItem(it.key, stringResource(SyncScheduleLabels.titleOf(it)))
    }

    val scheduleNote = stringResource(uploadScheduleNoteRes(mode))
    WithInfo(stringResource(R.string.wizard_schedule_mode_label), scheduleNote) {
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_mode_label),
                selected = modeOptions.find { it.key == mode.key } ?: modeOptions.first(),
                options = modeOptions,
                onOptionSelected = { actions.setSyncScheduleMode(SyncScheduleMode.fromKey(it.key)) }
            )
        }
    }

    if (mode == SyncScheduleMode.WEEKLY) {
        val dayOptions = SyncScheduleLabels.DAY_OF_WEEK_OPTIONS.map { day ->
            OptionItem(day.toString(), stringResource(SyncScheduleLabels.dayOfWeekOf(day)))
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_day_label),
                selected = dayOptions.find { it.key == dayOfWeek.toString() } ?: dayOptions.first(),
                options = dayOptions,
                onOptionSelected = { actions.setSyncDayOfWeek(it.key.toIntOrNull() ?: 2) }
            )
        }
    }

    if (mode != SyncScheduleMode.IMMEDIATE) {
        val hourOptions = (0..23).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
        val minuteOptions = SyncScheduleLabels.MINUTE_OPTIONS.map {
            OptionItem(it.toString(), it.toString().padStart(2, '0'))
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_hour_label),
                selected = hourOptions.find { it.key == hour.toString() } ?: hourOptions.first(),
                options = hourOptions,
                onOptionSelected = { actions.setSyncTimeHour(it.key.toIntOrNull() ?: 2) }
            )
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_minute_label),
                selected = minuteOptions.find { it.key == minute.toString() } ?: minuteOptions.first(),
                options = minuteOptions,
                onOptionSelected = { actions.setSyncTimeMinute(it.key.toIntOrNull() ?: 0) }
            )
        }
    }

    ExplanationParagraph(scheduleNote)
}

/** The sentence under the upload schedule: what "immediately" means, or what the schedule is for. */
@StringRes
internal fun uploadScheduleNoteRes(mode: SyncScheduleMode): Int =
    if (mode == SyncScheduleMode.IMMEDIATE) R.string.wizard_schedule_immediate_note
    else R.string.wizard_schedule_subtitle

/**
 * A destructive settings change waiting on the user's confirmation, with the sentence that explains
 * what it will do. Carrying the message with the action is what stops the age period and the size
 * cap — which delete for different reasons — sharing one vague warning.
 */
private data class PendingDestructiveChange(
    @androidx.annotation.StringRes val messageRes: Int,
    val apply: () -> Unit
)

/**
 * "Skip calls shorter than". First in Retention, because it is the only control there that decides whether a
 * recording is ever kept at all — the rest decide how long a kept one lives. Not confirmed like the periods:
 * it takes nothing that already exists, so the worst a mistaken setting can do is stop capturing short calls
 * from now on, which is visible and instantly reversible.
 */
@Composable
internal fun MinDurationRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val minDurationSeconds = remember(updateTrigger) { preferences.getMinDurationSeconds() }
    val minDurationOptions = MinDurationPolicy.PRESET_SECONDS.map { seconds ->
        OptionItem(
            seconds.toString(),
            when (seconds) {
                0 -> stringResource(R.string.min_duration_off)
                60 -> stringResource(R.string.min_duration_one_minute)
                else -> stringResource(R.string.min_duration_seconds, seconds)
            }
        )
    }
    WithInfo(stringResource(R.string.min_duration_label), stringResource(R.string.min_duration_caption)) {
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.min_duration_label),
                selected = minDurationOptions.find { it.key == minDurationSeconds.toString() }
                    ?: minDurationOptions.first(),
                options = minDurationOptions,
                onOptionSelected = { actions.setMinDurationSeconds(it.key.toIntOrNull() ?: 0) }
            )
        }
    }
    SettingsExplanation(stringResource(R.string.min_duration_caption))
}

/**
 * Retention proper: the age periods (linked or per destination), the space limit and when the sweep runs,
 * each destructive change confirmed first. The confirmation dialog lives here with the rows it guards.
 */
@Composable
internal fun RetentionPolicyRows(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val capBytes = remember(updateTrigger) { preferences.getStorageCapBytes() }
    val capOptions = StorageCapPolicy.PRESET_BYTES.map { bytes ->
        OptionItem(
            bytes.toString(),
            if (bytes == 0L) stringResource(R.string.storage_cap_off)
            else stringResource(R.string.storage_cap_gigabytes, bytes / (1L shl 30))
        )
    }
    val linked = remember(updateTrigger) { preferences.isRetentionLinked() }
    val localDays = remember(updateTrigger) { preferences.getRetentionLocalDays() }
    val driveDays = remember(updateTrigger) { preferences.getRetentionDriveDays() }

    val options = RetentionPeriod.entries.map { OptionItem(it.days.toString(), stringResource(it.labelRes)) }
    fun optionFor(days: Int) =
        options.find { it.key == RetentionPeriod.fromDays(days).days.toString() } ?: options.first()

    // Turning either destructive setting on from OFF is confirmed first. The message differs: an age
    // period deletes by age and the cap deletes by size, and one sentence covering both would be
    // vague exactly where the user is being asked to authorise deleting their own recordings.
    var pendingConfirm by remember { mutableStateOf<PendingDestructiveChange?>(null) }
    fun applyOrConfirm(wasOff: Boolean, newDays: Int, apply: () -> Unit) {
        if (wasOff && newDays > 0) {
            pendingConfirm = PendingDestructiveChange(R.string.retention_confirm_message, apply)
        } else {
            apply()
        }
    }

    SettingsToggleRow(
        label = stringResource(R.string.retention_linked_label),
        checked = linked,
        onCheckedChange = { nowLinked ->
            actions.setRetentionLinked(nowLinked)
            // When linking, unify the Drive period to the device one so they match.
            if (nowLinked) actions.setRetentionDriveDays(localDays)
        }
    )

    SettingsDivider()

    if (linked) {
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.retention_period_label),
                selected = optionFor(localDays),
                options = options,
                onOptionSelected = { opt ->
                    val days = opt.key.toIntOrNull() ?: 0
                    applyOrConfirm(wasOff = localDays == 0 && driveDays == 0, newDays = days) {
                        actions.setRetentionLocalDays(days)
                        actions.setRetentionDriveDays(days)
                    }
                }
            )
        }
    } else {
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.retention_local_label),
                selected = optionFor(localDays),
                options = options,
                onOptionSelected = { opt ->
                    val days = opt.key.toIntOrNull() ?: 0
                    applyOrConfirm(wasOff = localDays == 0, newDays = days) { actions.setRetentionLocalDays(days) }
                }
            )
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.retention_drive_label),
                selected = optionFor(driveDays),
                options = options,
                onOptionSelected = { opt ->
                    val days = opt.key.toIntOrNull() ?: 0
                    applyOrConfirm(wasOff = driveDays == 0, newDays = days) { actions.setRetentionDriveDays(days) }
                }
            )
        }
    }

    SettingsDivider()

    // Device only. A Drive copy costs nothing on this phone and has its own quota, so deleting
    // one to free space here would be indefensible — and because only the device copy goes, a
    // recording that is also in Drive keeps its transcript and its place in the list.
    WithInfo(stringResource(R.string.storage_cap_label), stringResource(R.string.storage_cap_caption)) {
    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.storage_cap_label),
            selected = capOptions.find { it.key == capBytes.toString() } ?: capOptions.first(),
            options = capOptions,
            onOptionSelected = { opt ->
                val bytes = opt.key.toLongOrNull() ?: 0L
                if (capBytes == 0L && bytes > 0L) {
                    pendingConfirm = PendingDestructiveChange(R.string.storage_cap_confirm_message) {
                        actions.setStorageCapBytes(bytes)
                    }
                } else {
                    actions.setStorageCapBytes(bytes)
                }
            }
        )
    }
    }
    SettingsExplanation(stringResource(R.string.storage_cap_caption))

    // Sweep time — only relevant once retention is enabled. Two dropdowns (Hour/Minute) in the
    // device's LOCAL time zone, mirroring the sync-schedule picker. The cap counts: it runs in
    // this same daily sweep, so a cap-only setup still needs to be able to say when.
    if (localDays > 0 || driveDays > 0 || capBytes > 0L) {
        SettingsDivider()
        val hour = remember(updateTrigger) { preferences.getRetentionTimeHour() }
        val minute = remember(updateTrigger) { preferences.getRetentionTimeMinute() }
        val hourOptions = (0..23).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
        val minuteOptions = listOf(0, 15, 30, 45).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
        Text(
            text = stringResource(R.string.retention_time_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_hour_label),
                selected = hourOptions.find { it.key == hour.toString() } ?: hourOptions.first(),
                options = hourOptions,
                onOptionSelected = { actions.setRetentionTimeHour(it.key.toIntOrNull() ?: 0) }
            )
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_minute_label),
                selected = minuteOptions.find { it.key == minute.toString() } ?: minuteOptions.first(),
                options = minuteOptions,
                onOptionSelected = { actions.setRetentionTimeMinute(it.key.toIntOrNull() ?: 0) }
            )
        }
    }

    // In ⓘ mode this is behind the "Cleaning up" card's ⓘ (see groupExplanationText).
    ExplanationParagraph(stringResource(R.string.retention_description))

    pendingConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(stringResource(R.string.retention_confirm_title)) },
            text = { Text(stringResource(confirm.messageRes)) },
            confirmButton = {
                TextButton(onClick = { confirm.apply(); pendingConfirm = null }) {
                    Text(stringResource(R.string.retention_confirm_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}
