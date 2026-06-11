/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.R
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.system.PersistentFolderPickerContract
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.takePersistableFolderPermission
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.common.ToggleListItem
import com.baba.callvault.ui.viewmodels.WizardViewModel
import com.baba.callvault.utils.FILE_NAME_TEMPLATE_PRESETS
import com.baba.callvault.utils.fileNameTemplateExample
import com.baba.callvault.utils.presetForTemplateOrFirst

/** The audio bit-rate options offered in the wizard (bps), shared with Settings. */
private val WIZARD_BITRATE_OPTIONS = listOf(8000, 16000, 32000, 64000, 128000)

/** Minute granularity offered in the schedule step. */
private val WIZARD_MINUTE_OPTIONS = listOf(0, 15, 30, 45)

/** java.util.Calendar day-of-week constants (SUNDAY=1..SATURDAY=7). */
private val WIZARD_DAY_OF_WEEK_OPTIONS = (1..7).toList()

/**
 * The one-time post-onboarding setup wizard.
 *
 * Connects the [WizardViewModel] to a stepper UI and the SAF folder pickers, then calls [onFinished]
 * after the wizard persists everything (so the router can refresh and advance to Home).
 *
 * @param onFinished Called after the final "Finish" step completes; the router triggers a nav refresh.
 * @param modifier   Optional layout modifier for the root [Surface].
 */
@Composable
fun WizardScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WizardViewModel = viewModel()
) {
    val context = LocalContext.current
    val updateTrigger by viewModel.updateTrigger.collectAsState()

    // Device/recording folder picker — persists access across reboots.
    val recordingFolderPicker = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            context.takePersistableFolderPermission(uri)
            viewModel.setRecordingFolderUri(uri)
        }
    }

    // Drive folder picker — same contract.
    val driveFolderPicker = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            context.takePersistableFolderPermission(uri)
            viewModel.setDriveFolderUri(uri)
        }
    }

    // Current values (re-read whenever a write bumps updateTrigger).
    val storageTarget = remember(updateTrigger) { viewModel.preferences.getStorageTarget() }
    val recordingFolderLabel =
        remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, viewModel.preferences.getRecordingFolderUri()) }
    val driveFolderLabel =
        remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, viewModel.preferences.getDriveFolderUri()) }
    val scheduleMode = remember(updateTrigger) { viewModel.preferences.getSyncScheduleMode() }
    val syncHour = remember(updateTrigger) { viewModel.preferences.getSyncTimeHour() }
    val syncMinute = remember(updateTrigger) { viewModel.preferences.getSyncTimeMinute() }
    val syncDayOfWeek = remember(updateTrigger) { viewModel.preferences.getSyncDayOfWeek() }
    val autoRecordIncoming = remember(updateTrigger) { viewModel.preferences.isAutoRecordIncomingEnabled() }
    val autoRecordOutgoing = remember(updateTrigger) { viewModel.preferences.isAutoRecordOutgoingEnabled() }
    val audioCodec = remember(updateTrigger) { viewModel.preferences.getAudioCodec() }
    val audioBitRate = remember(updateTrigger) { viewModel.preferences.getAudioBitRate() }
    val fileNameTemplate = remember(updateTrigger) { viewModel.preferences.getFileNameTemplate() }

    val usesDrive = storageTarget == StorageTarget.DRIVE || storageTarget == StorageTarget.BOTH

    // The visible step list. The schedule step is only present when Drive is involved, so steps and
    // indices stay coherent regardless of storage target.
    val steps = remember(usesDrive) {
        buildList {
            add(WizardStep.STORAGE)
            if (usesDrive) add(WizardStep.SCHEDULE)
            add(WizardStep.AUTO_RECORD)
            add(WizardStep.AUDIO)
            add(WizardStep.FILE_NAME)
        }
    }

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    // Clamp in case the step list shrank (e.g. user switched away from Drive on step 1).
    val safeIndex = stepIndex.coerceIn(0, steps.lastIndex)
    val currentStep = steps[safeIndex]

    // Whether the current step's requirements are satisfied (gates the Next/Finish button).
    val canAdvance = when (currentStep) {
        WizardStep.STORAGE -> {
            val hasRecordingFolder = recordingFolderLabel != null
            val hasDriveFolder = !usesDrive || driveFolderLabel != null
            hasRecordingFolder && hasDriveFolder
        }
        else -> true
    }

    val isLastStep = safeIndex == steps.lastIndex

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                text = stringResource(R.string.wizard_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.wizard_step_of, safeIndex + 1, steps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (safeIndex + 1f) / steps.size },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Scrollable step body.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (currentStep) {
                    WizardStep.STORAGE -> StorageStep(
                        storageTarget = storageTarget,
                        recordingFolderLabel = recordingFolderLabel,
                        driveFolderLabel = driveFolderLabel,
                        usesDrive = usesDrive,
                        onSelectStorageTarget = viewModel::setStorageTarget,
                        onPickRecordingFolder = { recordingFolderPicker.launch(null) },
                        onPickDriveFolder = { driveFolderPicker.launch(null) }
                    )
                    WizardStep.SCHEDULE -> ScheduleStep(
                        scheduleMode = scheduleMode,
                        hour = syncHour,
                        minute = syncMinute,
                        dayOfWeek = syncDayOfWeek,
                        onSelectMode = viewModel::setSyncScheduleMode,
                        onSelectHour = viewModel::setSyncTimeHour,
                        onSelectMinute = viewModel::setSyncTimeMinute,
                        onSelectDayOfWeek = viewModel::setSyncDayOfWeek
                    )
                    WizardStep.AUTO_RECORD -> AutoRecordStep(
                        incoming = autoRecordIncoming,
                        outgoing = autoRecordOutgoing,
                        onIncomingChange = viewModel::setAutoRecordIncoming,
                        onOutgoingChange = viewModel::setAutoRecordOutgoing
                    )
                    WizardStep.AUDIO -> AudioStep(
                        audioCodec = audioCodec,
                        audioBitRate = audioBitRate,
                        onSelectCodec = viewModel::setAudioCodec,
                        onSelectBitRate = viewModel::setAudioBitRate
                    )
                    WizardStep.FILE_NAME -> FileNameStep(
                        template = fileNameTemplate,
                        onSelectTemplate = viewModel::setFileNameTemplate
                    )
                }
            }

            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Stepper controls.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { if (safeIndex > 0) stepIndex = safeIndex - 1 },
                    enabled = safeIndex > 0,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.wizard_back)) }

                Button(
                    onClick = {
                        if (isLastStep) {
                            viewModel.finish()
                            onFinished()
                        } else {
                            stepIndex = safeIndex + 1
                        }
                    },
                    enabled = canAdvance,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(
                            if (isLastStep) R.string.wizard_finish else R.string.wizard_next
                        )
                    )
                }
            }
        }
    }
}

/** The logical steps of the wizard (the schedule step is conditionally included). */
private enum class WizardStep { STORAGE, SCHEDULE, AUTO_RECORD, AUDIO, FILE_NAME }

// ── Steps ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun WizardStepCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun StepBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun FolderPickerRow(label: String, chosenName: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = chosenName ?: stringResource(R.string.settings_tap_to_select_folder),
            style = MaterialTheme.typography.bodyMedium,
            color = if (chosenName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onClick) {
            Text(stringResource(R.string.wizard_choose_folder))
        }
    }
}

@Composable
private fun StorageStep(
    storageTarget: StorageTarget,
    recordingFolderLabel: String?,
    driveFolderLabel: String?,
    usesDrive: Boolean,
    onSelectStorageTarget: (StorageTarget) -> Unit,
    onPickRecordingFolder: () -> Unit,
    onPickDriveFolder: () -> Unit
) {
    WizardStepCard(title = stringResource(R.string.wizard_storage_title)) {
        StepBody(stringResource(R.string.wizard_storage_subtitle))

        val options = StorageTarget.entries.map { target ->
            val labelRes = when (target) {
                StorageTarget.LOCAL -> R.string.storage_target_local
                StorageTarget.DRIVE -> R.string.storage_target_drive
                StorageTarget.BOTH -> R.string.storage_target_both
            }
            OptionItem(target.key, stringResource(labelRes))
        }
        M3DropdownField(
            label = stringResource(R.string.settings_storage_target_label),
            selected = options.find { it.key == storageTarget.key } ?: options.first(),
            options = options,
            onOptionSelected = { onSelectStorageTarget(StorageTarget.fromKey(it.key)) }
        )

        if (usesDrive) {
            StepBody(stringResource(R.string.wizard_storage_drive_note))
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

        FolderPickerRow(
            label = stringResource(R.string.settings_recording_folder_label),
            chosenName = recordingFolderLabel,
            onClick = onPickRecordingFolder
        )

        if (usesDrive) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            FolderPickerRow(
                label = stringResource(R.string.settings_drive_folder_label),
                chosenName = driveFolderLabel,
                onClick = onPickDriveFolder
            )
        }
    }
}

@Composable
private fun ScheduleStep(
    scheduleMode: SyncScheduleMode,
    hour: Int,
    minute: Int,
    dayOfWeek: Int,
    onSelectMode: (SyncScheduleMode) -> Unit,
    onSelectHour: (Int) -> Unit,
    onSelectMinute: (Int) -> Unit,
    onSelectDayOfWeek: (Int) -> Unit
) {
    WizardStepCard(title = stringResource(R.string.wizard_schedule_title)) {
        StepBody(stringResource(R.string.wizard_schedule_subtitle))

        val modeOptions = SyncScheduleMode.entries.map { mode ->
            val labelRes = when (mode) {
                SyncScheduleMode.IMMEDIATE -> R.string.wizard_schedule_immediate
                SyncScheduleMode.DAILY -> R.string.wizard_schedule_daily
                SyncScheduleMode.WEEKLY -> R.string.wizard_schedule_weekly
            }
            OptionItem(mode.key, stringResource(labelRes))
        }
        M3DropdownField(
            label = stringResource(R.string.wizard_schedule_mode_label),
            selected = modeOptions.find { it.key == scheduleMode.key } ?: modeOptions.first(),
            options = modeOptions,
            onOptionSelected = { onSelectMode(SyncScheduleMode.fromKey(it.key)) }
        )

        if (scheduleMode == SyncScheduleMode.IMMEDIATE) {
            StepBody(stringResource(R.string.wizard_schedule_immediate_note))
        }

        if (scheduleMode == SyncScheduleMode.WEEKLY) {
            val dayOptions = WIZARD_DAY_OF_WEEK_OPTIONS.map { day ->
                OptionItem(day.toString(), stringResource(dayOfWeekLabelRes(day)))
            }
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_day_label),
                selected = dayOptions.find { it.key == dayOfWeek.toString() } ?: dayOptions.first(),
                options = dayOptions,
                onOptionSelected = { onSelectDayOfWeek(it.key.toInt()) }
            )
        }

        if (scheduleMode == SyncScheduleMode.DAILY || scheduleMode == SyncScheduleMode.WEEKLY) {
            val hourOptions = (0..23).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_hour_label),
                selected = hourOptions.find { it.key == hour.toString() } ?: hourOptions.first(),
                options = hourOptions,
                onOptionSelected = { onSelectHour(it.key.toInt()) }
            )
            val minuteOptions = WIZARD_MINUTE_OPTIONS.map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_minute_label),
                selected = minuteOptions.find { it.key == minute.toString() } ?: minuteOptions.first(),
                options = minuteOptions,
                onOptionSelected = { onSelectMinute(it.key.toInt()) }
            )
        }
    }
}

@Composable
private fun AutoRecordStep(
    incoming: Boolean,
    outgoing: Boolean,
    onIncomingChange: (Boolean) -> Unit,
    onOutgoingChange: (Boolean) -> Unit
) {
    WizardStepCard(title = stringResource(R.string.wizard_auto_record_title)) {
        StepBody(stringResource(R.string.wizard_auto_record_subtitle))
        ToggleListItem(
            label = stringResource(R.string.settings_auto_record_incoming),
            checked = incoming,
            onCheckedChange = onIncomingChange
        )
        ToggleListItem(
            label = stringResource(R.string.settings_auto_record_outgoing),
            checked = outgoing,
            onCheckedChange = onOutgoingChange
        )
    }
}

@Composable
private fun AudioStep(
    audioCodec: String,
    audioBitRate: Int,
    onSelectCodec: (String) -> Unit,
    onSelectBitRate: (Int) -> Unit
) {
    WizardStepCard(title = stringResource(R.string.wizard_audio_title)) {
        StepBody(stringResource(R.string.wizard_audio_subtitle))

        val codecOptions = ScrcpyAudioCodec.entries.map { OptionItem(it.cliKey, stringResource(it.titleResId)) }
        M3DropdownField(
            label = stringResource(R.string.settings_audio_codec),
            selected = codecOptions.find { it.key == audioCodec } ?: codecOptions.first(),
            options = codecOptions,
            onOptionSelected = { onSelectCodec(it.key) }
        )

        val bitrateOptions = WIZARD_BITRATE_OPTIONS.map {
            OptionItem(it.toString(), stringResource(R.string.audio_bitrate_kbps, it / 1000))
        }
        M3DropdownField(
            label = stringResource(R.string.settings_audio_bitrate),
            selected = bitrateOptions.find { it.key == audioBitRate.toString() } ?: bitrateOptions.first(),
            options = bitrateOptions,
            onOptionSelected = { onSelectBitRate(it.key.toInt()) }
        )
    }
}

@Composable
private fun FileNameStep(
    template: String,
    onSelectTemplate: (String) -> Unit
) {
    WizardStepCard(title = stringResource(R.string.wizard_filename_title)) {
        StepBody(stringResource(R.string.wizard_filename_subtitle))

        val options = FILE_NAME_TEMPLATE_PRESETS.map { OptionItem(it.template, stringResource(it.labelRes)) }
        val selectedTemplate = presetForTemplateOrFirst(template).template
        M3DropdownField(
            label = stringResource(R.string.settings_file_name_template_preset),
            selected = options.find { it.key == selectedTemplate } ?: options.first(),
            options = options,
            onOptionSelected = { onSelectTemplate(it.key) }
        )

        StepBody(
            stringResource(
                R.string.settings_file_name_template_example,
                fileNameTemplateExample(selectedTemplate)
            )
        )
    }
}

/** Maps a java.util.Calendar day-of-week constant (SUNDAY=1..SATURDAY=7) to a label string resource. */
private fun dayOfWeekLabelRes(day: Int): Int = when (day) {
    1 -> R.string.wizard_day_sunday
    2 -> R.string.wizard_day_monday
    3 -> R.string.wizard_day_tuesday
    4 -> R.string.wizard_day_wednesday
    5 -> R.string.wizard_day_thursday
    6 -> R.string.wizard_day_friday
    else -> R.string.wizard_day_saturday
}
