/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.R
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.system.PersistentFolderPickerContract
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.takePersistableFolderPermission
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvHero
import com.baba.callvault.ui.common.CvPrimaryButton
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSecondaryButton
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
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
 * The one-time post-onboarding setup wizard, redesigned on the "Signal" design system.
 *
 * A guided, premium stepper: a teal segmented progress bar + "Step N of M" header sit above a
 * [CvHero] step title, branded [CvCard] option rows form each step body, and a persistent bottom
 * bar carries Back / Next (Finish on the last step). The [WizardViewModel] persists every choice
 * live; [onFinished] fires after the final step so the router refreshes and advances to Home.
 *
 * Behavior, persistence, the dynamic step list (the schedule step appears only for Drive/Both),
 * the clamp logic, and the Next-gating on required folders are all preserved from the original.
 *
 * @param onFinished Called after the final "Finish" step completes; the router triggers a nav refresh.
 * @param modifier   Optional layout modifier for the root [CvScaffold].
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
            if (SafHelper.isCloudFolder(uri)) {
                Toast.makeText(context, context.getString(R.string.folder_cloud_rejected), Toast.LENGTH_LONG).show()
            } else {
                context.takePersistableFolderPermission(uri)
                viewModel.setRecordingFolderUri(uri)
            }
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

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.app_name),
        bottomBar = {
            WizardBottomBar(
                isFirstStep = safeIndex == 0,
                isLastStep = isLastStep,
                canAdvance = canAdvance,
                onBack = { if (safeIndex > 0) stepIndex = safeIndex - 1 },
                onNext = {
                    if (isLastStep) {
                        viewModel.finish()
                        onFinished()
                    } else {
                        stepIndex = safeIndex + 1
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                WizardHeader(
                    stepNumber = safeIndex + 1,
                    stepCount = steps.size,
                    title = stringResource(stepTitleRes(currentStep)),
                    subtitle = stringResource(stepSubtitleRes(currentStep))
                )
            }

            item {
                when (currentStep) {
                    WizardStep.STORAGE -> StorageStep(
                        storageTarget = storageTarget,
                        recordingFolderLabel = recordingFolderLabel,
                        driveFolderLabel = driveFolderLabel,
                        usesDrive = usesDrive,
                        onSelectStorageTarget = viewModel::setStorageTarget,
                        // Seed each picker with its OWN current folder so it opens there, instead of
                        // letting Android's DocumentsUI reopen at the last-browsed location (which, after
                        // setting Drive, made re-picking the local folder open at the Drive path).
                        onPickRecordingFolder = { recordingFolderPicker.launch(viewModel.preferences.getRecordingFolderUri()) },
                        onPickDriveFolder = { driveFolderPicker.launch(viewModel.preferences.getDriveFolderUri()) }
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
        }
    }
}

/** The logical steps of the wizard (the schedule step is conditionally included). */
private enum class WizardStep { STORAGE, SCHEDULE, AUTO_RECORD, AUDIO, FILE_NAME }

// ── Shell: header + progress + bottom bar ─────────────────────────────────────────────────────

/**
 * The guided header: a small "Setup" eyebrow + "Step N of M", a teal segmented progress bar, then
 * the current step's title + subtitle via [CvHero]. This anchors the wizard as a confident, modern
 * onboarding flow rather than a stock Material stepper.
 */
@Composable
private fun WizardHeader(stepNumber: Int, stepCount: Int, title: String, subtitle: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.wizard_ui_eyebrow).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.wizard_step_of, stepNumber, stepCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        SegmentedProgress(current = stepNumber, total = stepCount)
        Spacer(Modifier.height(20.dp))
        CvHero(title = title, subtitle = subtitle)
    }
}

/** A row of rounded teal segments showing progress; completed/current segments are filled teal. */
@Composable
private fun SegmentedProgress(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        val fill = MaterialTheme.colorScheme.primary
        for (i in 1..total) {
            val color by animateColorAsState(
                targetValue = if (i <= current) fill else track,
                label = "wizardSegment$i"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** Persistent bottom bar: tonal Back (hidden on step 1) + filled teal Next / Finish. */
@Composable
private fun WizardBottomBar(
    isFirstStep: Boolean,
    isLastStep: Boolean,
    canAdvance: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isFirstStep) {
            CvSecondaryButton(
                text = stringResource(R.string.wizard_back),
                onClick = onBack,
                modifier = Modifier.weight(1f)
            )
        }
        CvPrimaryButton(
            text = stringResource(if (isLastStep) R.string.wizard_finish else R.string.wizard_next),
            onClick = onNext,
            enabled = canAdvance,
            leadingIcon = if (isLastStep) Icons.Filled.Done else Icons.AutoMirrored.Filled.ArrowForward,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Reusable branded option / folder / toggle rows ────────────────────────────────────────────

/**
 * A branded radio-style option card: title + one-line description, a teal check that appears when
 * selected, and a teal border + faint teal tint in the selected state. Replaces stock RadioButtons.
 */
@Composable
private fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val container =
        if (selected) primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
    val borderColor by animateColorAsState(
        targetValue = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
        label = "optionBorder"
    )

    CvCard(
        onClick = onClick,
        color = container,
        border = false,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.border(
            width = if (selected) 1.5.dp else 1.dp,
            color = borderColor,
            shape = MaterialTheme.shapes.large
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            SelectionDot(selected = selected)
        }
    }
}

/** A circular selection indicator: a hollow ring when unselected, a filled teal check when selected. */
@Composable
private fun SelectionDot(selected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    if (selected) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
    }
}

/**
 * A tappable folder picker row: a leading icon, the label + chosen folder name (or a "Required"
 * pill prompting selection), and a trailing chevron. Required-but-unset rows read with a coral tint.
 */
@Composable
private fun FolderPickerCard(
    icon: ImageVector,
    label: String,
    chosenName: String?,
    onClick: () -> Unit
) {
    val hasFolder = chosenName != null
    CvCard(onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chosenName ?: stringResource(R.string.wizard_ui_folder_choose),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasFolder) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** A clear toggle row: title + one-line description on the left, a teal [Switch] on the right. */
@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    CvCard(onClick = { onCheckedChange(!checked) }, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }
    }
}

/** A muted helper/note line set inside a faint surface card — for contextual guidance. */
@Composable
private fun NoteCard(text: String) {
    CvCard(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = false,
        contentPadding = PaddingValues(14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Steps ─────────────────────────────────────────────────────────────────────────────────────

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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StorageTarget.entries.forEach { target ->
            OptionCard(
                title = stringResource(storageTargetTitleRes(target)),
                description = stringResource(storageTargetDescRes(target)),
                selected = storageTarget == target,
                onClick = { onSelectStorageTarget(target) }
            )
        }

        if (usesDrive) {
            NoteCard(stringResource(R.string.wizard_storage_drive_note))
        }

        Spacer(Modifier.height(2.dp))

        FolderPickerCard(
            icon = Icons.Filled.Folder,
            label = stringResource(R.string.settings_recording_folder_label),
            chosenName = recordingFolderLabel,
            onClick = onPickRecordingFolder
        )

        if (usesDrive) {
            FolderPickerCard(
                icon = Icons.Filled.CloudUpload,
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SyncScheduleMode.entries.forEach { mode ->
            OptionCard(
                title = stringResource(scheduleModeTitleRes(mode)),
                description = stringResource(scheduleModeDescRes(mode)),
                selected = scheduleMode == mode,
                onClick = { onSelectMode(mode) }
            )
        }

        if (scheduleMode == SyncScheduleMode.IMMEDIATE) {
            NoteCard(stringResource(R.string.wizard_schedule_immediate_note))
        }

        if (scheduleMode == SyncScheduleMode.DAILY || scheduleMode == SyncScheduleMode.WEEKLY) {
            CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
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
}

@Composable
private fun AutoRecordStep(
    incoming: Boolean,
    outgoing: Boolean,
    onIncomingChange: (Boolean) -> Unit,
    onOutgoingChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleCard(
            title = stringResource(R.string.settings_auto_record_incoming),
            description = stringResource(R.string.wizard_ui_auto_incoming_desc),
            checked = incoming,
            onCheckedChange = onIncomingChange
        )
        ToggleCard(
            title = stringResource(R.string.settings_auto_record_outgoing),
            description = stringResource(R.string.wizard_ui_auto_outgoing_desc),
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
    CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
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
    // Friendly preset label as primary text + a resolved example as the per-item preview line —
    // never the raw "{token}" template.
    val options = FILE_NAME_TEMPLATE_PRESETS.map {
        OptionItem(
            key = it.template,
            label = stringResource(it.labelRes),
            description = stringResource(
                R.string.settings_file_name_template_example,
                fileNameTemplateExample(it.template)
            )
        )
    }
    val selectedTemplate = presetForTemplateOrFirst(template).template

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
            M3DropdownField(
                label = stringResource(R.string.settings_file_name_template_preset),
                selected = options.find { it.key == selectedTemplate } ?: options.first(),
                options = options,
                onOptionSelected = { onSelectTemplate(it.key) }
            )
        }
        NoteCard(
            stringResource(
                R.string.settings_file_name_template_example,
                fileNameTemplateExample(selectedTemplate)
            )
        )
    }
}

// ── String-resource mappers ───────────────────────────────────────────────────────────────────

private fun stepTitleRes(step: WizardStep): Int = when (step) {
    WizardStep.STORAGE -> R.string.wizard_storage_title
    WizardStep.SCHEDULE -> R.string.wizard_schedule_title
    WizardStep.AUTO_RECORD -> R.string.wizard_auto_record_title
    WizardStep.AUDIO -> R.string.wizard_audio_title
    WizardStep.FILE_NAME -> R.string.wizard_filename_title
}

private fun stepSubtitleRes(step: WizardStep): Int = when (step) {
    WizardStep.STORAGE -> R.string.wizard_storage_subtitle
    WizardStep.SCHEDULE -> R.string.wizard_schedule_subtitle
    WizardStep.AUTO_RECORD -> R.string.wizard_auto_record_subtitle
    WizardStep.AUDIO -> R.string.wizard_audio_subtitle
    WizardStep.FILE_NAME -> R.string.wizard_filename_subtitle
}

private fun storageTargetTitleRes(target: StorageTarget): Int = when (target) {
    StorageTarget.LOCAL -> R.string.storage_target_local
    StorageTarget.DRIVE -> R.string.storage_target_drive
    StorageTarget.BOTH -> R.string.storage_target_both
}

private fun storageTargetDescRes(target: StorageTarget): Int = when (target) {
    StorageTarget.LOCAL -> R.string.wizard_ui_storage_local_desc
    StorageTarget.DRIVE -> R.string.wizard_ui_storage_drive_desc
    StorageTarget.BOTH -> R.string.wizard_ui_storage_both_desc
}

private fun scheduleModeTitleRes(mode: SyncScheduleMode): Int = when (mode) {
    SyncScheduleMode.IMMEDIATE -> R.string.wizard_schedule_immediate
    SyncScheduleMode.DAILY -> R.string.wizard_schedule_daily
    SyncScheduleMode.WEEKLY -> R.string.wizard_schedule_weekly
}

private fun scheduleModeDescRes(mode: SyncScheduleMode): Int = when (mode) {
    SyncScheduleMode.IMMEDIATE -> R.string.wizard_ui_schedule_immediate_desc
    SyncScheduleMode.DAILY -> R.string.wizard_ui_schedule_daily_desc
    SyncScheduleMode.WEEKLY -> R.string.wizard_ui_schedule_weekly_desc
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
