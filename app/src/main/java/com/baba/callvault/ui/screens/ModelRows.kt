/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.transcription.model.DownloadNetwork
import com.baba.callvault.transcription.model.ModelDownloadWorker
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.ui.common.ConfirmDialog
import com.baba.callvault.ui.common.ModelDownloadState
import com.baba.callvault.ui.common.rememberMobileDataDownloadPrompt
import com.baba.callvault.ui.common.rememberModelDownloadState

/**
 * The speech model's rows: what is on the device, and the one action that makes sense next.
 *
 * Its own file rather than inline in `SettingsScreen`, because the setup wizard renders exactly
 * these rows too. The wizard used to say "the button is in Settings" and stop there; now it offers
 * the download, and a second hand-rolled copy of these six states is precisely the drift that has
 * already cost this codebase an offline toggle without its capability gate and a USB probe that
 * switched Wireless debugging on. One composable, two screens, one truth about the download.
 *
 * **Every state comes from [rememberModelDownloadState], which observes WorkManager.** The rows this
 * replaced read `ModelRepository.installedModels` inside a `remember(updateTrigger)`, so a download
 * that started, progressed and finished while the screen was open changed nothing on it — Settings
 * flipped from "Download model" to "Downloaded and ready" only if something else happened to bump
 * the trigger. Progress that is only correct on entry is not progress.
 *
 * The import row (GitHub #58) sits under the download row wherever a download could be started,
 * because a copy already on hand is the same answer to "get the model" at none of the network cost.
 *
 * @param model         The tier currently selected, which is the one all of this is about.
 * @param updateTrigger Re-reads the filesystem when it changes; deleting a model touches no work.
 * @param onBackup      Null hides the backup row: the wizard runs on a fresh install, with nothing to save.
 */
@Composable
internal fun TranscriptionModelRows(
    model: TranscriptionModel,
    updateTrigger: Int,
    onDownload: (TranscriptionModel, DownloadNetwork) -> Unit,
    onCancel: (TranscriptionModel) -> Unit,
    onDelete: (TranscriptionModel) -> Unit,
    onImport: (TranscriptionModel, Uri) -> Unit,
    onCancelImport: (TranscriptionModel) -> Unit,
    onBackup: ((TranscriptionModel, Uri) -> Unit)? = null,
    onCancelBackup: (TranscriptionModel) -> Unit = {}
) {
    val state by rememberModelDownloadState(model, refreshKey = updateTrigger)
    // Every download and resume below goes through this, so on mobile data each one asks first.
    // The size reads exactly as the download row's subtitle does.
    val askNetwork = rememberMobileDataDownloadPrompt()
    val sizeText = stringResource(R.string.transcription_model_download_subtitle, model.sizeBytes / BYTES_PER_MB)
    val download: (TranscriptionModel) -> Unit = { chosen ->
        askNetwork(sizeText) { network -> onDownload(chosen, network) }
    }
    // Above the `when`, so a state change while the picker is open cannot drop the pick.
    val launchPicker = rememberModelImportPicker { onImport(model, it) }
    val importRow: @Composable (String?) -> Unit = { failure ->
        ModelImportRow(model = model, launchPicker = launchPicker, failureText = failure)
    }

    Column(Modifier.fillMaxWidth()) {
        when (val current = state) {
            // The model is the gate: nothing can be transcribed until one is on the device, so its
            // state is stated plainly rather than left for the user to infer from a button that
            // does nothing.
            ModelDownloadState.Installed -> {
                NavigationRow(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.transcription_model_delete),
                    value = stringResource(R.string.transcription_model_installed),
                    onClick = { onDelete(model) }
                )
                if (onBackup != null) {
                    ModelBackupRow(model, onPicked = { onBackup(model, it) }, onCancel = { onCancelBackup(model) })
                }
            }

            ModelDownloadState.Absent -> {
                TranscriptionDownloadRow(model, download)
                importRow(null)
            }

            // Stopped part-way. Saying so is the point: those bytes are banked and the server
            // resumes from exactly that offset, so a row reading "Download, 574 MB" would hide
            // what the user has already paid for. Same reasoning as the summariser's rows.
            //
            // Discard has its own row (GitHub #60). This comment used to say "the delete row below
            // reclaims it", but no delete row is shown in this state, so a paused download could
            // only be removed by clearing the app's data.
            is ModelDownloadState.Paused -> {
                TranscriptionResumeRow(model, current.downloadedBytes, download)
                TranscriptionDiscardRow(model, current.downloadedBytes, onDelete)
                importRow(null)
            }

            // Queued and 0% look identical on a bar and mean different things, so waiting says so
            // in words and shows an indeterminate bar rather than an empty one.
            ModelDownloadState.Waiting -> ModelDownloadingRow(
                label = stringResource(R.string.transcription_model_waiting),
                percent = null,
                onCancel = { onCancel(model) }
            )

            is ModelDownloadState.Downloading -> ModelDownloadingRow(
                label = stringResource(R.string.transcription_model_downloading, current.percent),
                percent = current.percent,
                onCancel = { onCancel(model) }
            )

            // One thing at a time: an import holds the model's write lock, so a download started
            // beside it would only wait. The bar and its cancel are the whole of what makes sense.
            is ModelDownloadState.Importing -> ModelImportingRow(
                percent = current.percent,
                onCancel = { onCancelImport(model) }
            )

            // The download row is offered plainly — nothing about the download failed — and the
            // import row says what went wrong with the file, where tapping it picks another.
            is ModelDownloadState.ImportFailed -> {
                if (current.partialBytes > 0L) {
                    TranscriptionResumeRow(model, current.partialBytes, download)
                    TranscriptionDiscardRow(model, current.partialBytes, onDelete)
                } else {
                    TranscriptionDownloadRow(model, download)
                }
                importRow(importFailureText(current.reason, model, current.otherModelId))
            }

            // A failure says which one it was, because the two the user can act on need different
            // actions: free up space, or simply try again.
            is ModelDownloadState.Failed -> {
                NavigationRow(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.transcription_model_download),
                    value = stringResource(
                        when (current.reason) {
                            ModelDownloadWorker.ERROR_NO_SPACE -> R.string.transcription_model_failed_space
                            ModelDownloadWorker.ERROR_VERIFICATION_FAILED -> R.string.transcription_model_failed_verify
                            else -> R.string.transcription_model_failed
                        }
                    ),
                    onClick = { download(model) }
                )
                importRow(null)
            }
        }
    }
}

/** "Resume the download", with what is already banked: the row for a model stopped part-way. */
@Composable
private fun TranscriptionResumeRow(
    model: TranscriptionModel,
    downloadedBytes: Long,
    onDownload: (TranscriptionModel) -> Unit
) {
    NavigationRow(
        icon = Icons.Filled.Download,
        label = stringResource(R.string.transcription_model_resume),
        value = stringResource(
            R.string.transcription_model_resume_subtitle,
            downloadedBytes / BYTES_PER_MB,
            model.sizeBytes / BYTES_PER_MB
        ),
        onClick = { onDownload(model) }
    )
}

/**
 * "Discard what was downloaded": throws away a paused download, after asking (GitHub #60).
 *
 * The wording is the summariser's (`summary_model_discard*`, `summary_discard_confirm_*`). None of it
 * names the model, so both share one set of strings and no locale needs a new translation.
 */
@Composable
private fun TranscriptionDiscardRow(
    model: TranscriptionModel,
    downloadedBytes: Long,
    onDelete: (TranscriptionModel) -> Unit
) {
    val gigabytes = downloadedBytes / BYTES_PER_GB
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.summary_discard_confirm_title),
            body = stringResource(R.string.summary_discard_confirm_body, gigabytes),
            confirmLabel = stringResource(R.string.summary_discard_confirm_confirm),
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onDelete(model)
            }
        )
    }
    NavigationRow(
        icon = Icons.Filled.Delete,
        label = stringResource(R.string.summary_model_discard),
        value = stringResource(R.string.summary_model_discard_subtitle, gigabytes),
        onClick = { confirming = true }
    )
}

/** The summariser's unit (SummarySection's `toGigabytes`), so both discard rows read the same. */
private const val BYTES_PER_GB = 1_000_000_000f

/** "Download the model", with its size: the row for a model with nothing on disk. */
@Composable
private fun TranscriptionDownloadRow(model: TranscriptionModel, onDownload: (TranscriptionModel) -> Unit) {
    NavigationRow(
        icon = Icons.Filled.Download,
        label = stringResource(R.string.transcription_model_download),
        value = stringResource(R.string.transcription_model_download_subtitle, model.sizeBytes / BYTES_PER_MB),
        onClick = { onDownload(model) }
    )
}

/**
 * A download in flight, with the one action that makes sense while it runs.
 *
 * Shared by both models' rows: the summariser is 3.46 GB and the speech model 190-874 MB, but a bar
 * and a Cancel is the whole of what either needs, and two of them would drift on the colour trap
 * below.
 */
@Composable
internal fun ModelDownloadingRow(
    label: String,
    percent: Int?,
    onCancel: () -> Unit,
    cancelLabel: String = stringResource(R.string.summary_model_cancel)
) {
    // Both colours are stated. Left to the theme, M3 resolves the track to CoralDeep and the bar
    // renders teal-on-pink, which reads as an error rather than as progress — the same trap that
    // has already produced a maroon tonal button, a maroon selected card and a red progress ring
    // in this codebase. Observed here too, on the emulator, before it was pinned down.
    val accent = MaterialTheme.colorScheme.primary
    val track = accent.copy(alpha = TRACK_ALPHA)
    val barModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)

    Column(Modifier.fillMaxWidth()) {
        NavigationRow(
            icon = Icons.Filled.Close,
            label = label,
            // By default the summariser's string, said of both downloads. "Cancel the download" is
            // not about which model it is, and minting a second identical string would only mean
            // translating the same sentence twice into ten locales. An import or a backup is not a
            // download, so those pass the plain "Cancel" instead.
            value = cancelLabel,
            onClick = onCancel
        )
        // Determinate only once there is a real figure; an empty bar sitting at zero reads as stuck.
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / PERCENT },
                modifier = barModifier,
                color = accent,
                trackColor = track
            )
        } else {
            LinearProgressIndicator(
                modifier = barModifier,
                color = accent,
                trackColor = track
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private const val PERCENT = 100f

/** How much of the accent shows through as the bar's unfilled track. */
private const val TRACK_ALPHA = 0.20f
