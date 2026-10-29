/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.system.storage.SyncScheduler
import com.baba.callvault.transcription.model.DownloadNetwork
import com.baba.callvault.transcription.model.DownloadableModel
import com.baba.callvault.transcription.model.ModelDownloadWorker
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.ModelTransferWorker
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The "Brain" of the one-time setup [com.baba.callvault.ui.screens.WizardScreen].
 *
 * Mirrors [SettingsViewModel]'s style: holds an [AppPreferences] instance and persists every choice
 * live as the user changes it. A single [updateTrigger] `StateFlow` is bumped on each write so the
 * stateless wizard UI recomposes and re-reads the latest values from [preferences].
 */
class WizardViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    /** Read/write access to user preferences. Exposed so the wizard UI can read current values. */
    val preferences = AppPreferences(appContext)

    private val _updateTrigger = MutableStateFlow(0)

    /** Bumped after every write so the wizard UI recomposes and re-reads [preferences]. */
    val updateTrigger: StateFlow<Int> = _updateTrigger.asStateFlow()

    /** Forces the wizard UI to recompose and re-read preference-backed state. */
    private fun bump() = _updateTrigger.update { it + 1 }

    // ------ Step 1: storage mode + folders

    /** Persists the storage routing target (Local / Drive / Both). */
    fun setStorageTarget(target: StorageTarget) {
        preferences.setStorageTarget(target)
        bump()
    }

    /** Persists the user-selected device/recording folder URI. */
    fun setRecordingFolderUri(uri: Uri?) {
        preferences.setRecordingFolderUri(uri)
        bump()
    }

    /** Persists the user-selected Google Drive SAF folder URI. */
    fun setDriveFolderUri(uri: Uri?) {
        preferences.setDriveFolderUri(uri)
        bump()
    }

    // ------ Step 2: schedule

    /** Persists the cloud sync cadence (Immediate / Daily / Weekly). */
    fun setSyncScheduleMode(mode: SyncScheduleMode) {
        preferences.setSyncScheduleMode(mode)
        bump()
    }

    /** Persists the scheduled sweep hour (0-23). */
    fun setSyncTimeHour(hour: Int) {
        preferences.setSyncTimeHour(hour)
        bump()
    }

    /** Persists the scheduled sweep minute (0-59). */
    fun setSyncTimeMinute(minute: Int) {
        preferences.setSyncTimeMinute(minute)
        bump()
    }

    /** Persists the scheduled sweep day-of-week (java.util.Calendar: SUNDAY=1..SATURDAY=7). */
    fun setSyncDayOfWeek(day: Int) {
        preferences.setSyncDayOfWeek(day)
        bump()
    }

    // ------ Step 3: auto-record

    /** Persists whether incoming calls are auto-recorded. */
    /** Persists whether CallVault handles carrier calls at all; false is "app calls only". */
    fun setCarrierRecording(enabled: Boolean) {
        preferences.setCarrierRecordingEnabled(enabled)
        bump()
    }

    fun setAutoRecordIncoming(enabled: Boolean) {
        preferences.setAutoRecordIncomingEnabled(enabled)
        bump()
    }

    /** Persists whether outgoing calls are auto-recorded. */
    fun setAutoRecordOutgoing(enabled: Boolean) {
        preferences.setAutoRecordOutgoingEnabled(enabled)
        bump()
    }

    // ------ Step 4: audio quality

    /** Persists the audio codec CLI key (e.g. "opus", "aac"). */
    fun setAudioCodec(codec: String) {
        // Adopts the codec's recommended bit rate, exactly as the Settings screen does. It used not
        // to, so a codec chosen here kept the previous one's rate — issue #28c.
        preferences.chooseAudioCodec(codec)
        bump()
    }

    /** Persists the audio bit rate in bps. */
    fun setAudioBitRate(bitRate: Int) {
        preferences.setAudioBitRate(bitRate)
        bump()
    }

    // ------ Step 5: file name format

    /** Persists the recording file-name template (one of the shared presets). */
    fun setFileNameTemplate(template: String) {
        preferences.setFileNameTemplate(template)
        bump()
    }

    // ------ Step 6: transcription + summaries

    /** Persists the chosen speech-model tier, which is the one the download row then fetches. */
    fun setTranscriptionModelId(id: String) {
        preferences.setTranscriptionModelId(id)
        bump()
    }

    /**
     * Queues a model for download and leaves it running.
     *
     * **Deliberately not tied to this screen.** [ModelDownloadWorker] is unique WorkManager work
     * enqueued against the application context (Wi-Fi only unless the user chose mobile data), so it
     * outlives the wizard, the Activity and — with a foreground download in flight — a good deal
     * else. That is the whole point: the summariser is 3.46 GB, the wizard has four steps left, and
     * making either wait for the other would be absurd. Nothing here cancels on navigation, and
     * [finish] does not touch it.
     *
     * The same call Settings makes, so a download started here shows its progress there and vice
     * versa: both screens read it back through `rememberModelDownloadState`, which observes the
     * work rather than the calendar.
     */
    fun downloadModel(model: DownloadableModel, network: DownloadNetwork) {
        ModelDownloadWorker.enqueue(appContext, model, network)
        bump()
    }

    /** Stops a download in progress. The partial file is kept, so resuming does not re-fetch it. */
    fun cancelModelDownload(model: DownloadableModel) {
        ModelDownloadWorker.cancel(appContext, model)
        bump()
    }

    /**
     * Imports a model from a file the user picked (GitHub #58) — the same call Settings makes, so
     * either screen shows its progress. The grant is made persistable first, so a job WorkManager
     * re-runs after the picker's temporary grant has gone can still read the file.
     */
    fun importModel(model: DownloadableModel, source: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { AppLogger.w("CV:Wizard", "No persistable read grant for the import of ${model.id}: ${it.javaClass.simpleName}") }
        ModelTransferWorker.enqueueImport(appContext, model, source)
        bump()
    }

    /** Stops an import; the worker releases the file's grant once the job has let go of it. */
    fun cancelModelImport(model: DownloadableModel) {
        ModelTransferWorker.cancelImport(appContext, model)
        bump()
    }

    /** Removes a downloaded model and any partial download of it. */
    fun deleteModel(model: DownloadableModel) {
        ModelDownloadWorker.cancel(appContext, model)
        ModelRepository.delete(appContext, model)
        bump()
    }

    // ------ Finish

    /**
     * Finalises the wizard: applies the sync schedule from the now-persisted prefs and marks the
     * wizard as completed. Most settings were persisted live; this only reconciles the periodic
     * sweep and flips the completion flag. The caller (router) then triggers a nav refresh so the
     * router advances to Home.
     */
    fun finish() {
        // All individual prefs were persisted live above. Reconcile the periodic sweep and gate.
        SyncScheduler.apply(appContext)
        preferences.setWizardCompleted(true)
        bump()
    }
}
