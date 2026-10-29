/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import androidx.work.NetworkType
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.transcription.cloud.TranscriptionService
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.TranscriptionModel

/**
 * The few decisions that differ between transcribing on the phone and with a cloud service (issue #48),
 * as pure functions so each is testable without WorkManager, a model or a network.
 */
object TranscriptionRouting {

    /** What a transcription run should do with the current settings. */
    enum class Route {
        /** whisper.cpp on this phone, with the installed model. */
        ON_DEVICE,

        /** A cloud service; no local model is needed. */
        CLOUD,

        /** On this phone, but the model is not on it yet: wait (the download may be in flight). */
        WAIT_FOR_MODEL,
    }

    fun route(service: TranscriptionService, modelInstalled: Boolean): Route = when {
        service.isCloud -> Route.CLOUD
        modelInstalled -> Route.ON_DEVICE
        else -> Route.WAIT_FOR_MODEL
    }

    /**
     * Whether a tap on Transcribe can start now — the gate before the "No speech model yet" dialog.
     * A cloud service never needs the model; a missing key fails that one run with a clear message.
     */
    fun canStart(context: Context): Boolean {
        val prefs = AppPreferences(context)
        val service = prefs.getTranscriptionService()
        if (service.isCloud) return true
        val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId()) ?: TranscriptionModel.DEFAULT
        return ModelRepository.isInstalled(context, model)
    }

    /**
     * 📐 What a cloud run is quoted as: a few seconds of setup plus a tenth of the call. A guess with
     * headroom, not a measurement — it covers the local decode and the upload; the service itself
     * usually answers in seconds. Not learned per phone: a network's speed is not a property of it.
     */
    val CLOUD_RUN_COST = TranscriptionEstimate.RunCost(loadMs = 3_000L, rtf = 0.1)

    /** The run cost to quote for the current settings. */
    fun runCost(prefs: AppPreferences): TranscriptionEstimate.RunCost {
        if (prefs.getTranscriptionService().isCloud) return CLOUD_RUN_COST
        val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId()) ?: TranscriptionModel.DEFAULT
        return prefs.getRunCost(model)
    }

    /** Whether to word the estimate as "may take a while": only an on-device model never timed here. */
    fun isFirstRun(prefs: AppPreferences): Boolean {
        if (prefs.getTranscriptionService().isCloud) return false
        val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId()) ?: TranscriptionModel.DEFAULT
        return !prefs.hasMeasuredRun(model.id)
    }

    /** The WorkManager constraints one run is held to. */
    data class RunConstraints(val requiresCharging: Boolean, val networkType: NetworkType)

    /**
     * On the phone, no network is needed and charging follows the setting. A cloud run also needs a
     * network: unmetered (Wi-Fi) unless the user allowed mobile data. Charging still follows the
     * setting — the local decode is real CPU on a long call, and the user chose that rule.
     */
    fun constraints(isCloud: Boolean, allowMobileData: Boolean, requiresCharging: Boolean): RunConstraints =
        RunConstraints(
            requiresCharging = requiresCharging,
            networkType = when {
                !isCloud -> NetworkType.NOT_REQUIRED
                allowMobileData -> NetworkType.CONNECTED
                else -> NetworkType.UNMETERED
            },
        )

    /**
     * How many times a cloud run that met a passing failure (429, 5xx, no network) is retried with
     * WorkManager's back-off before the recording is marked failed. Without a cap a service that stays
     * down would be retried for ever, hours apart, with nothing on screen saying why.
     */
    const val MAX_CLOUD_ATTEMPTS = 5

    /** Whether this attempt may still end in a retry rather than a failure. */
    fun mayRetryCloud(runAttemptCount: Int): Boolean = runAttemptCount + 1 < MAX_CLOUD_ATTEMPTS
}
