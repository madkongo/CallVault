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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.baba.callvault.R
import kotlinx.coroutines.flow.first
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.TranscriptionMode
import com.baba.callvault.transcription.TranscriptionQueue
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.ui.common.TranscriptionLabels
import com.baba.callvault.ui.common.TranscriptionTimeDialog
import com.baba.callvault.ui.common.formatRunTime
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.viewmodels.SettingsActions
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Translate

/**
 * On-device transcription: whether it happens on its own, when, and with which model.
 *
 * The mode dropdown comes first and the scheduling rows appear only under Automatic, so a user who
 * wants to transcribe the occasional call by hand is never shown a run time they do not use. Same
 * shape as [UploadScheduleSubSection], which already does exactly this for the upload cadence.
 *
 * Model and language stay visible in both modes: a manual transcription needs them just as much as an
 * automatic one, and the model has to be downloaded before either can work at all.
 */
@Composable
internal fun TranscriptionSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** The summariser's rows, rendered last — see the call site for why they live in here. */
    summaryRows: @Composable () -> Unit = {}
) {
    // The rows are below, one composable each, shared with the new Settings screen's
    // "Model and language" and "When to transcribe" groups.
    SettingsSection(
        title = stringResource(R.string.settings_section_transcription_and_summaries),
        expanded = expanded,
        onToggle = onToggle
    ) {
        TranscriptionScheduleRows(preferences, updateTrigger, actions)

        TranscriptionModelChoiceRow(preferences, updateTrigger, actions)

        TranscriptionLanguageRows(preferences, updateTrigger, actions)

        TranscriptionConfirmRow(preferences, updateTrigger, actions)

        TranscriptionModelDownloadRows(preferences, updateTrigger, actions)

        TranscriptionModeNote(preferences, updateTrigger)

        // Last, after everything transcription needs: a summary is read from a transcript, so the
        // order on screen is the order of the dependency.
        summaryRows()
    }
}

/**
 * When transcription runs: the mode, then the run time and batch size (automatic only), then "only while
 * charging" (any automatic mode).
 */
@Composable
internal fun TranscriptionScheduleRows(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val mode = remember(updateTrigger) { preferences.getTranscriptionMode() }
    val hour = remember(updateTrigger) { preferences.getTranscriptionHour() }
    val minute = remember(updateTrigger) { preferences.getTranscriptionMinute() }
    val requiresCharging = remember(updateTrigger) { preferences.getTranscriptionRequiresCharging() }
    val batchLimit = remember(updateTrigger) { preferences.getTranscriptionBatchLimit() }
    var showTimePicker by remember { mutableStateOf(false) }

    TranscriptionModeField(mode = mode, onModeChange = { actions.setTranscriptionMode(it) })

    // Only the scheduled sweep has a run time and a backlog size to choose. Per-call has neither:
    // it runs when a call ends, and only ever on that one call.
    if (mode == TranscriptionMode.AUTOMATIC) {
        NavigationRow(
            icon = Icons.Filled.Schedule,
            label = stringResource(R.string.transcription_time_label),
            value = formatRunTime(hour, minute),
            onClick = { showTimePicker = true }
        )

        if (showTimePicker) {
            TranscriptionTimeDialog(
                hour = hour,
                minute = minute,
                onDismiss = { showTimePicker = false },
                onConfirm = { h, m ->
                    actions.setTranscriptionHour(h)
                    actions.setTranscriptionMinute(m)
                    showTimePicker = false
                }
            )
        }

        val batchOptions = TranscriptionLabels.BATCH_LIMIT_OPTIONS.map { limit ->
            OptionItem(
                limit.toString(),
                if (limit == TranscriptionQueue.NO_LIMIT) {
                    stringResource(R.string.transcription_batch_no_limit)
                } else {
                    limit.toString()
                }
            )
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.transcription_batch_label),
                selected = batchOptions.find { it.key == batchLimit.toString() } ?: batchOptions.first(),
                options = batchOptions,
                onOptionSelected = { actions.setTranscriptionBatchLimit(it.key.toIntOrNull() ?: 25) }
            )
        }
    }

    // Shown for both automatic modes. It matters most for per-call, where a call can end anywhere:
    // the run then waits for a charger rather than being skipped.
    if (mode != TranscriptionMode.MANUAL) {
        SettingsToggleRow(
            icon = Icons.Filled.BatteryChargingFull,
            label = stringResource(R.string.transcription_charging_title),
            description = stringResource(R.string.transcription_charging_subtitle),
            checked = requiresCharging,
            onCheckedChange = { actions.setTranscriptionRequiresCharging(it) }
        )
    }
}

/** The speech-model dropdown, reading and writing the stored model id. */
@Composable
internal fun TranscriptionModelChoiceRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val modelId = remember(updateTrigger) { preferences.getTranscriptionModelId() }
    TranscriptionModelField(
        modelId = modelId,
        onModelChange = { actions.setTranscriptionModelId(it) }
    )
}

/**
 * The one language transcripts and summaries share, and "ask me each time" directly under the language it
 * overrides, because it only makes sense as an answer to "but this call was in another language".
 */
@Composable
internal fun TranscriptionLanguageRows(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val language = remember(updateTrigger) { preferences.getTranscriptionLanguage() }
    val askLanguage = remember(updateTrigger) { preferences.getTranscriptionAskLanguage() }

    TranscriptionLanguageField(
        language = language,
        onLanguageChange = { chosen ->
            // Clears the summary's own override as well as setting the transcription language.
            // The override used to be a second, separate setting; anyone who set it before this
            // merge would otherwise keep summarising in that old language for ever, with nothing
            // on screen to say so.
            preferences.setSummaryLanguage(null)
            actions.setTranscriptionLanguage(chosen)
        }
    )

    SettingsToggleRow(
        icon = Icons.Filled.Translate,
        label = stringResource(R.string.transcription_ask_language_title),
        description = stringResource(R.string.transcription_ask_language_subtitle),
        checked = askLanguage,
        onCheckedChange = { actions.setTranscriptionAskLanguage(it) }
    )
}

/**
 * Ask before transcribing. Its home is here so the "don't ask again" checkbox in the dialog can be undone
 * — a dialog that can permanently remove itself with no way back would be a trap.
 */
@Composable
internal fun TranscriptionConfirmRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val confirmBeforeRun = remember(updateTrigger) { preferences.getTranscriptionConfirmBeforeRun() }
    SettingsToggleRow(
        icon = Icons.Filled.Schedule,
        label = stringResource(R.string.transcription_confirm_title),
        description = stringResource(R.string.transcription_confirm_subtitle),
        checked = confirmBeforeRun,
        onCheckedChange = { actions.setTranscriptionConfirmBeforeRun(it) }
    )
}

/**
 * Download, cancel or delete the chosen speech model. Shared with the wizard, which offers the same
 * download. These rows also replaced a pair that read `installedModels` once per updateTrigger: a download
 * that ran while this section was open showed nothing at all, and only reached "Downloaded and ready" if
 * some unrelated setting happened to bump the trigger.
 */
@Composable
internal fun TranscriptionModelDownloadRows(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val modelId = remember(updateTrigger) { preferences.getTranscriptionModelId() }
    val selectedModel = TranscriptionModel.fromId(modelId) ?: TranscriptionModel.DEFAULT
    TranscriptionModelRows(
        model = selectedModel,
        updateTrigger = updateTrigger,
        onDownload = { model, network -> actions.downloadTranscriptionModel(model, network) },
        onCancel = { actions.cancelTranscriptionModelDownload(it) },
        onDelete = { actions.deleteTranscriptionModel(it) },
        onImport = { model, uri -> actions.importModel(model, uri) },
        onCancelImport = { actions.cancelModelImport(it) },
        onBackup = { model, uri -> actions.backUpModel(model, uri) },
        onCancelBackup = { actions.cancelModelBackup(it) }
    )
}

/** The paragraph saying what the chosen mode means in practice. */
@Composable
internal fun TranscriptionModeNote(preferences: AppPreferences, updateTrigger: Int) {
    val mode = remember(updateTrigger) { preferences.getTranscriptionMode() }
    // In ⓘ mode this is behind the "When to transcribe" card's ⓘ (see groupExplanationText).
    ExplanationParagraph(stringResource(transcriptionModeNoteRes(mode)))
}

/** What the chosen transcription mode means in practice. */
internal fun transcriptionModeNoteRes(mode: TranscriptionMode): Int = when (mode) {
    TranscriptionMode.MANUAL -> R.string.transcription_note_manual
    TranscriptionMode.AFTER_EACH_CALL -> R.string.transcription_note_after_each_call
    TranscriptionMode.AUTOMATIC -> R.string.transcription_note_automatic
}

/** Bytes in a megabyte, for stating a model's download size. Shared with the wizard's own cost note. */
internal const val BYTES_PER_MB = 1_000_000L

/**
 * The speech-model dropdown, listing every tier in the catalogue.
 *
 * Extracted for the setup wizard, which offers the download and therefore has to let the user say
 * *which* model is being fetched — 190 MB against 874 MB is the difference between a minute and
 * several on a slow connection, and between gist Hebrew and clean Hebrew afterwards. Shared rather
 * than copied for the same reason as [TranscriptionModeField]: the row that starts the download
 * reads this setting, so two dropdowns writing it would be two chances to disagree about what is
 * being downloaded.
 *
 * @param modelId The stored id; anything unrecognised falls back to [TranscriptionModel.DEFAULT].
 */
@Composable
internal fun TranscriptionModelField(modelId: String, onModelChange: (String) -> Unit) {
    val modelOptions = TranscriptionModel.entries.map {
        OptionItem(it.id, stringResource(TranscriptionLabels.titleOf(it)))
    }
    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.transcription_model_label),
            // DEFAULT, not `modelOptions.first()`. Every other reader of this preference —
            // TranscriptionWorker, HomeScreen, the wizard, the progress estimate — resolves an
            // unrecognised id to DEFAULT, and this one resolving it to whichever tier happens to be
            // declared first meant the dropdown could name a different model from the one that would
            // actually run. Harmless while nothing writes a bad id, and a silent trap the moment
            // something does; it also stopped being merely theoretical once the enum's first entry
            // and its DEFAULT were no longer one tier apart.
            selected = modelOptions.find { it.key == modelId }
                ?: modelOptions.first { it.key == TranscriptionModel.DEFAULT.id },
            options = modelOptions,
            onOptionSelected = { onModelChange(it.key) }
        )
    }
}

/**
 * The "Transcribe: manually / after each call / automatically" dropdown, with the cost warning that
 * guards the two automatic modes.
 *
 * Shared with the setup wizard rather than copied into it. The warning **is** the control here — a
 * hand-rolled second dropdown would be one edit away from persisting an automatic mode with nothing
 * said about what it costs, which is the same drift the hand-rolled offline toggle produced.
 *
 * @param onModeChange Called only once the choice is settled — i.e. after the warning is accepted,
 *   never on merely opening it. Callers persist AND reconcile `TranscriptionScheduler` from it.
 */
@Composable
internal fun TranscriptionModeField(
    mode: TranscriptionMode,
    onModeChange: (TranscriptionMode) -> Unit,
) {
    val modeOptions = TranscriptionMode.entries.map {
        OptionItem(it.key, stringResource(TranscriptionLabels.titleOf(it)))
    }

    // Turning an automatic mode ON is confirmed; turning it off never is. The cost being warned
    // about is the cost of it running, so there is nothing to warn about when stopping.
    var pendingAutoMode by remember { mutableStateOf<TranscriptionMode?>(null) }

    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.transcription_mode_label),
            selected = modeOptions.find { it.key == mode.key } ?: modeOptions.first(),
            options = modeOptions,
            onOptionSelected = { option ->
                val chosen = TranscriptionMode.fromKey(option.key)
                if (chosen != TranscriptionMode.MANUAL && chosen != mode) {
                    pendingAutoMode = chosen
                } else {
                    onModeChange(chosen)
                }
            }
        )
    }

    pendingAutoMode?.let { chosen ->
        AlertDialog(
            onDismissRequest = { pendingAutoMode = null },
            icon = { Icon(imageVector = Icons.Filled.BatteryChargingFull, contentDescription = null) },
            title = { Text(stringResource(R.string.transcription_warning_title)) },
            text = { Text(stringResource(R.string.transcription_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onModeChange(chosen)
                    pendingAutoMode = null
                }) {
                    Text(stringResource(R.string.transcription_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAutoMode = null }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}

/**
 * The transcription-language dropdown, and the one thing a dropdown alone cannot say: that auto-detect
 * is not a neutral option.
 *
 * The hint appears only while auto-detect is selected. It is a warning about a state, not a label for
 * the control, and a line that sat under every language would read as a scold to the majority who never
 * chose auto-detect at all.
 *
 * Shared with the setup wizard for the same reason as [TranscriptionModeField]: the warning must not be
 * the half that a second copy forgets.
 *
 * @param language The pinned language, null meaning auto-detect.
 * @param onLanguageChange Given the new language, null meaning auto-detect.
 */
@Composable
internal fun TranscriptionLanguageField(
    language: String?,
    onLanguageChange: (String?) -> Unit,
) {
    // Sorted by the translated name, so the list reads A-Z in whatever language the app is in.
    val languageOptions = TranscriptionLabels
        .sortLanguageOptions(
            TranscriptionLabels.LANGUAGE_OPTIONS.map { code ->
                (code ?: TranscriptionLabels.AUTO_DETECT_KEY) to
                    stringResource(TranscriptionLabels.languageOf(code))
            }
        )
        .map { (key, label) -> OptionItem(key, label) }

    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.transcription_language_label),
            selected = languageOptions.find {
                it.key == (language ?: TranscriptionLabels.AUTO_DETECT_KEY)
            } ?: languageOptions.first(),
            options = languageOptions,
            onOptionSelected = {
                onLanguageChange(it.key.takeIf { k -> k != TranscriptionLabels.AUTO_DETECT_KEY })
            }
        )
        if (language == null) {
            HintText(stringResource(R.string.transcription_language_auto_hint))
        }
    }
}
