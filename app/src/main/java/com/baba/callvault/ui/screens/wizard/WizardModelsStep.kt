/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.transcription.model.DownloadNetwork
import com.baba.callvault.transcription.model.DownloadableModel
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.ui.common.TranscriptionLabels
import com.baba.callvault.ui.common.CvCard

/**
 * Transcription and summaries: when they run, in which language, and — now — their models.
 *
 * Here at all because a fresh install has no other route to either feature — the What's New dialog
 * needs an *update* to fire, and the wizard cannot be re-run, so a step omitted here leaves the
 * flagship of this release buried in a Settings accordion nobody was told to open.
 *
 * Every control is Settings' own composable, not a wizard copy of it: the mode dropdown carries the
 * "this is slow and heavy" confirmation, the language dropdown carries the auto-detect warning, and
 * the summariser's rows carry the requirements dialog that guards 3.46 GB. A copy that lost any of
 * those would be a copy that lies — the same reason [ExperimentalStep] shares its toggle and
 * [ReliabilityStep] shares the offline switch, both after a hand-rolled version had already dropped
 * the half that mattered.
 *
 * **The downloads start here and nothing waits for them.** The step used to say the button was in
 * Settings, which asked someone who has just been told about the feature to go and find it later.
 * They are WorkManager jobs (Wi-Fi only unless the user says yes to mobile data at the tap), so Next and Finish stay enabled throughout
 * ([canAdvanceFrom] never asks about this step) and the download carries on while the user finishes
 * setup and lands on Home.
 *
 * @param updateTrigger Bumped by the view model after each write, which re-reads the filesystem
 *   under the model rows — deleting a model touches no WorkManager state, so nothing else would.
 */
@Composable
internal fun ModelsStep(
    updateTrigger: Int,
    onDownloadModel: (DownloadableModel, DownloadNetwork) -> Unit,
    onCancelModelDownload: (DownloadableModel) -> Unit,
    onDeleteModel: (DownloadableModel) -> Unit,
    onImportModel: (DownloadableModel, Uri) -> Unit,
    onCancelModelImport: (DownloadableModel) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var mode by remember { mutableStateOf(prefs.getTranscriptionMode()) }
    var language by remember { mutableStateOf(prefs.getTranscriptionLanguage()) }
    var modelId by remember { mutableStateOf(prefs.getTranscriptionModelId()) }
    val selectedModel = TranscriptionModel.fromId(modelId) ?: TranscriptionModel.DEFAULT

    // Built from the catalogue, so a new model tier is described without touching this.
    val modelSizes = TranscriptionModel.entries.map { model ->
        stringResource(
            R.string.wizard_transcription_model_size,
            stringResource(TranscriptionLabels.titleOf(model)),
            model.sizeBytes / BYTES_PER_MB
        )
    }.joinToString(stringResource(R.string.list_separator))
    // True only while the field still holds the phone's own language: once the user picks another
    // one (or auto-detect), "set to this phone's language" would be a false sentence.
    val languageIsPhoneDefault = language != null && language == AppPreferences.DefaultsValue.TRANSCRIPTION_LANGUAGE

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
            WithInfo(
                label = stringResource(R.string.transcription_mode_label),
                explanation = stringResource(transcriptionModeNoteRes(mode))
            ) {
                TranscriptionModeField(
                    mode = mode,
                    onModeChange = { chosen ->
                        mode = chosen
                        prefs.setTranscriptionMode(chosen)
                        // Reconcile the sweep immediately, exactly as Settings does: persisting the mode
                        // alone would leave WorkManager disagreeing with it until something else ran.
                        TranscriptionScheduler.apply(context)
                    }
                )
            }
            WithInfo(
                label = stringResource(R.string.transcription_language_label),
                explanation = if (languageIsPhoneDefault) {
                    stringResource(R.string.wizard_transcription_language_note)
                } else {
                    null
                }
            ) {
                TranscriptionLanguageField(
                    language = language,
                    onLanguageChange = { chosen ->
                        language = chosen
                        prefs.setTranscriptionLanguage(chosen)
                    }
                )
            }

            // Offered here because the download row below fetches whatever this says. Its ⓘ names the
            // sizes, so the difference between 190 MB and 874 MB is not a surprise on a metered month.
            WithInfo(
                label = stringResource(R.string.transcription_model_label),
                explanation = stringResource(R.string.wizard_transcription_model_sizes_note, modelSizes)
            ) {
                TranscriptionModelField(
                    modelId = modelId,
                    onModelChange = { chosen ->
                        modelId = chosen
                        prefs.setTranscriptionModelId(chosen)
                    }
                )
            }

            SettingsSubHeader(stringResource(R.string.settings_section_transcription))
            TranscriptionModelRows(
                model = selectedModel,
                updateTrigger = updateTrigger,
                onDownload = onDownloadModel,
                onCancel = onCancelModelDownload,
                onDelete = onDeleteModel,
                // No backup in setup: a fresh install has nothing to save yet.
                onImport = onImportModel,
                onCancelImport = onCancelModelImport
            )
        }

        // The rows are the summariser's own, so the requirements dialog (3.46 GB to download, about
        // 3.5 GB of memory to run) still stands between the tap and the fetch.
        CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
            val summariesLabel = stringResource(R.string.wizard_transcription_summary_heading)
            WithInfo(
                label = summariesLabel,
                explanation = stringResource(R.string.wizard_transcription_summary_body)
            ) {
                SettingsSubHeader(summariesLabel)
            }
            SummaryRows(
                updateTrigger = updateTrigger,
                onDownload = onDownloadModel,
                onCancel = onCancelModelDownload,
                onDelete = onDeleteModel,
                // No backup in setup: a fresh install has nothing to save yet.
                onImport = onImportModel,
                onCancelImport = onCancelModelImport
            )
        }
    }
}
