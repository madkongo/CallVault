/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.system.PersistentFolderPickerContract
import com.baba.callvault.system.storage.FolderOverlap
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.takePersistableFolderPermission
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvHero
import com.baba.callvault.ui.common.CvPrimaryButton
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSecondaryButton
import com.baba.callvault.ui.viewmodels.WizardViewModel

/** The audio bit-rate options offered in the wizard (bps), shared with Settings. */

/** Minute granularity offered in the schedule step. */

/** java.util.Calendar day-of-week constants (SUNDAY=1..SATURDAY=7). */

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
            } else if (FolderOverlap.overlaps(uri, viewModel.preferences.getDriveFolderUri())) {
                Toast.makeText(context, context.getString(R.string.folder_drive_overlaps_recordings), Toast.LENGTH_LONG).show()
            } else {
                context.takePersistableFolderPermission(uri)
                viewModel.setRecordingFolderUri(uri)
            }
        }
    }

    // Drive folder picker — same contract.
    val driveFolderPicker = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null && FolderOverlap.overlaps(viewModel.preferences.getRecordingFolderUri(), uri)) {
            // Same rule as Settings: the recording folder can never be the Drive folder. See [FolderOverlap].
            Toast.makeText(context, context.getString(R.string.folder_drive_overlaps_recordings), Toast.LENGTH_LONG).show()
        } else if (uri != null) {
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
    val carrierRecording = remember(updateTrigger) { viewModel.preferences.isCarrierRecordingEnabled() }
    val autoRecordIncoming = remember(updateTrigger) { viewModel.preferences.isAutoRecordIncomingEnabled() }
    val autoRecordOutgoing = remember(updateTrigger) { viewModel.preferences.isAutoRecordOutgoingEnabled() }
    val askAfterCall = remember(updateTrigger) { viewModel.preferences.getAfterCallMode() == AppPreferences.AfterCallMode.ASK }
    val audioCodec = remember(updateTrigger) { viewModel.preferences.getAudioCodec() }
    val audioBitRate = remember(updateTrigger) { viewModel.preferences.getAudioBitRate() }
    val fileNameTemplate = remember(updateTrigger) { viewModel.preferences.getFileNameTemplate() }

    val usesDrive = storageTarget == StorageTarget.DRIVE || storageTarget == StorageTarget.BOTH

    // Fixed for the run of the wizard: the mode is chosen during onboarding, before this screen opens.
    val usesEmbeddedAdb = remember { !viewModel.preferences.getPrivilegedMode().needsShizuku }

    val steps = remember(usesDrive, usesEmbeddedAdb) { wizardSteps(usesDrive, usesEmbeddedAdb) }

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    // Clamp in case the step list shrank (e.g. user switched away from Drive on step 1).
    val safeIndex = stepIndex.coerceIn(0, steps.lastIndex)
    val currentStep = steps[safeIndex]

    // Whether the current step's requirements are satisfied (gates the Next/Finish button).
    val canAdvance = canAdvanceFrom(
        step = currentStep,
        hasRecordingFolder = recordingFolderLabel != null,
        hasDriveFolder = !usesDrive || driveFolderLabel != null
    )

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
                    line = stringResource(stepLineRes(currentStep)),
                    explanation = stepInfo(currentStep)
                )
            }

            item {
                // Same switch as the new Settings: every explanation in a step sits behind an (i).
                CompositionLocalProvider(LocalSettingsDescriptionsAsInfo provides true) {
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
                        carrier = carrierRecording,
                        incoming = autoRecordIncoming,
                        outgoing = autoRecordOutgoing,
                        onCarrierChange = viewModel::setCarrierRecording,
                        onIncomingChange = viewModel::setAutoRecordIncoming,
                        onOutgoingChange = viewModel::setAutoRecordOutgoing,
                        askAfterCall = askAfterCall,
                        onAskAfterCallChange = viewModel::setAfterCallAsk,
                    )
                    WizardStep.RELIABILITY -> ReliabilityStep()
                    WizardStep.EXPERIMENTAL -> ExperimentalStep()
                    WizardStep.UPDATES -> UpdatesStep()
                    WizardStep.AUDIO -> AudioStep(
                        audioCodec = audioCodec,
                        audioBitRate = audioBitRate,
                        onSelectCodec = viewModel::setAudioCodec,
                        onSelectBitRate = viewModel::setAudioBitRate
                    )
                    WizardStep.FILE_NAME -> FileNameStep(
                        template = fileNameTemplate,
                        settingsVersion = updateTrigger,
                        onSelectTemplate = viewModel::setFileNameTemplate
                    )
                    WizardStep.TRANSCRIPTION -> ModelsStep(
                        updateTrigger = updateTrigger,
                        onDownloadModel = viewModel::downloadModel,
                        onCancelModelDownload = viewModel::cancelModelDownload,
                        onDeleteModel = viewModel::deleteModel,
                        onImportModel = viewModel::importModel,
                        onCancelModelImport = viewModel::cancelModelImport
                    )
                }
                }
            }
        }
    }
}

/** The logical steps of the wizard (two of them are conditionally included — see [wizardSteps]). */
internal enum class WizardStep { STORAGE, SCHEDULE, AUTO_RECORD, RELIABILITY, EXPERIMENTAL, AUDIO, FILE_NAME, TRANSCRIPTION, UPDATES }

/**
 * The visible step list, in order. Kept a pure function so the two exclusions are testable and stay
 * coherent with the "Step N of M" counter, which is derived from this list.
 *
 * @param usesDrive       Drive is a storage target, so there is an upload schedule worth asking about.
 * @param usesEmbeddedAdb CallVault's own ADB provides the privilege (i.e. not Shizuku mode).
 *
 * The reliability step is dropped without it. Nothing on that step exists in Shizuku mode — the USB
 * default is applied over the embedded shell and offline recording is the loopback opt-in — and merely
 * *showing* it did harm: entering the step probed the USB default, which ensured an ADB connection,
 * which switched **Wireless debugging on** for a user who had never paired anything and could never
 * have benefited. [UsbDefaultConfig.isShellUsable] now refuses that from below as well; this keeps the
 * user from being asked a question with no answer in the first place.
 */
internal fun wizardSteps(usesDrive: Boolean, usesEmbeddedAdb: Boolean): List<WizardStep> = buildList {
    add(WizardStep.STORAGE)
    if (usesDrive) add(WizardStep.SCHEDULE)
    add(WizardStep.AUTO_RECORD)
    if (usesEmbeddedAdb) add(WizardStep.RELIABILITY)
    add(WizardStep.EXPERIMENTAL)
    add(WizardStep.AUDIO)
    add(WizardStep.FILE_NAME)
    // Follows the recording's own life: captured, encoded, named, and then read. It is unconditional
    // because transcription needs nothing from ADB — it is the one flagship feature a fresh install
    // would otherwise never hear about, since the What's New dialog only fires after an *update*.
    add(WizardStep.TRANSCRIPTION)
    add(WizardStep.UPDATES)
}

/**
 * Whether Next (or Finish, on the last step) is enabled on [step].
 *
 * Storage is the only step that can withhold it, and only because a recording with nowhere to go is
 * not a setup that works. Everything else is a preference, an opt-in or — since the transcription
 * step started offering them — a download, and **a download must never gate setup**: the summariser
 * is 3.46 GB over an unmetered network, so a user on mobile data would be held on step seven of nine
 * until they got home. The work continues on its own; the wizard has no reason to wait for it.
 *
 * A pure function for the same reason [wizardSteps] is one — it is a rule about the wizard rather
 * than a piece of its drawing, and this is the shape a test can hold on to.
 */
internal fun canAdvanceFrom(
    step: WizardStep,
    hasRecordingFolder: Boolean,
    hasDriveFolder: Boolean
): Boolean = when (step) {
    WizardStep.STORAGE -> hasRecordingFolder && hasDriveFolder
    else -> true
}

// ── Shell: header + progress + bottom bar ─────────────────────────────────────────────────────

/**
 * The guided header: a small "Setup" eyebrow + "Step N of M", a teal segmented progress bar, then the
 * current step's title with an (i) beside it, and one short line under it. The (i) carries everything the
 * step used to say in its longer subtitle and note cards ([explanation]).
 */
@Composable
private fun WizardHeader(stepNumber: Int, stepCount: Int, title: String, line: String, explanation: String?) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (!explanation.isNullOrBlank()) SettingsInfoButton(label = title, explanation = explanation)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Everything a step says beyond its one-line header, for the (i) beside the title: the former subtitle,
 * then the former note cards. A note that used to show only for one choice (Drive storage, the immediate
 * upload mode, a pre-filled language) is now always part of this text.
 */
@Composable
private fun stepInfo(step: WizardStep): String? {
    val subtitle = stringResource(stepSubtitleRes(step))
    return when (step) {
        WizardStep.STORAGE -> joinExplanations(subtitle, stringResource(R.string.wizard_storage_drive_note))
        WizardStep.SCHEDULE -> joinExplanations(subtitle, stringResource(R.string.wizard_schedule_immediate_note))
        // Language and model-size notes live on their own rows' (i) in the step.
        WizardStep.TRANSCRIPTION -> joinExplanations(
            subtitle,
            stringResource(R.string.wizard_transcription_background_note)
        )
        else -> subtitle
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

// ── String-resource mappers ───────────────────────────────────────────────────────────────────

private fun stepTitleRes(step: WizardStep): Int = when (step) {
    WizardStep.STORAGE -> R.string.wizard_storage_title
    WizardStep.SCHEDULE -> R.string.wizard_schedule_title
    WizardStep.AUTO_RECORD -> R.string.wizard_auto_record_title
    WizardStep.RELIABILITY -> R.string.wizard_reliability_title
    WizardStep.EXPERIMENTAL -> R.string.wizard_experimental_title
    WizardStep.UPDATES -> R.string.wizard_updates_title
    WizardStep.AUDIO -> R.string.wizard_audio_title
    WizardStep.FILE_NAME -> R.string.wizard_filename_title
    WizardStep.TRANSCRIPTION -> R.string.wizard_transcription_title
}

private fun stepSubtitleRes(step: WizardStep): Int = when (step) {
    WizardStep.STORAGE -> R.string.wizard_storage_subtitle
    WizardStep.SCHEDULE -> R.string.wizard_schedule_subtitle
    WizardStep.AUTO_RECORD -> R.string.wizard_auto_record_subtitle
    WizardStep.RELIABILITY -> R.string.wizard_reliability_subtitle
    WizardStep.EXPERIMENTAL -> R.string.wizard_experimental_subtitle
    WizardStep.UPDATES -> R.string.wizard_updates_subtitle
    WizardStep.AUDIO -> R.string.wizard_audio_subtitle
    WizardStep.FILE_NAME -> R.string.wizard_filename_subtitle
    WizardStep.TRANSCRIPTION -> R.string.wizard_transcription_subtitle
}

private fun stepLineRes(step: WizardStep): Int = when (step) {
    WizardStep.STORAGE -> R.string.wizard_storage_line
    WizardStep.SCHEDULE -> R.string.wizard_schedule_line
    WizardStep.AUTO_RECORD -> R.string.wizard_auto_record_line
    WizardStep.RELIABILITY -> R.string.wizard_reliability_line
    WizardStep.EXPERIMENTAL -> R.string.wizard_experimental_line
    WizardStep.UPDATES -> R.string.wizard_updates_line
    WizardStep.AUDIO -> R.string.wizard_audio_line
    WizardStep.FILE_NAME -> R.string.wizard_filename_line
    WizardStep.TRANSCRIPTION -> R.string.wizard_transcription_line
}
