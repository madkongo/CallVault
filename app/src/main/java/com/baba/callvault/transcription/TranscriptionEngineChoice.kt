/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.transcription.cloud.CloudEndpoint
import com.baba.callvault.transcription.cloud.CloudError
import com.baba.callvault.transcription.cloud.CloudKeyStore
import com.baba.callvault.transcription.cloud.CloudTranscriber
import com.baba.callvault.transcription.cloud.TranscriptionService
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.TranscriptionModel

/**
 * Which engine a transcription uses, or why it cannot run yet. Shared by the queued worker and by "transcribe just
 * this part" when sharing a clip, so both always pick the same engine the user set up.
 */
sealed interface TranscriptionEngineChoice {
    data class Run(
        val modelId: String,
        val modelPath: String,
        /** Null for the default on-device engine. */
        val transcriber: Transcriber?,
        val isCloud: Boolean,
        val progressPercent: () -> Int,
    ) : TranscriptionEngineChoice

    data object WaitForModel : TranscriptionEngineChoice
    data object CloudNotSetUp : TranscriptionEngineChoice

    companion object {
        /**
         * On this phone with the installed model, or the chosen cloud service with its key (issue #48).
         *
         * A cloud service never waits for a model. Not set up (no key, or an incomplete Custom): a named
         * run still goes ahead so that one recording fails with a message saying what to fix; a sweep
         * does nothing, rather than fail every waiting call.
         */
        fun choose(
            context: Context,
            prefs: AppPreferences,
            model: TranscriptionModel,
            isNamedRun: Boolean,
            serviceOverride: TranscriptionService? = null,
        ): TranscriptionEngineChoice {
            val service = serviceOverride ?: prefs.getTranscriptionService()
            val modelPath = if (service.isCloud) null else ModelRepository.pathFor(context, model)?.absolutePath
            return when (TranscriptionRouting.route(service, modelInstalled = modelPath != null)) {
                TranscriptionRouting.Route.WAIT_FOR_MODEL -> WaitForModel
                TranscriptionRouting.Route.ON_DEVICE -> Run(
                    model.id, modelPath.orEmpty(), transcriber = null, isCloud = false,
                    progressPercent = { TranscriptionEngine.progressPercent() },
                )
                TranscriptionRouting.Route.CLOUD -> {
                    val endpoint = prefs.getCloudEndpoint(service)
                    // Consent is checked here too, not only in Settings: nothing sends audio without it.
                    val key = CloudKeyStore(context).get(service).takeIf { prefs.hasCloudConsent(service) }
                    if ((endpoint == null || key == null) && !isNamedRun) return CloudNotSetUp
                    val label = endpoint?.label ?: service.preset?.displayName ?: service.key
                    val transcriber = endpoint?.let { CloudTranscriber(it, key).asTranscriber() }
                        ?: Transcriber { _, _, _, _, _, _, _ -> throw CloudError.notSetUp(label) }
                    Run(
                        modelId = endpoint?.engineId ?: "${CloudEndpoint.ENGINE_PREFIX}${service.key}",
                        modelPath = "",
                        transcriber = transcriber,
                        isCloud = true,
                        progressPercent = { CloudTranscriber.progressPercent },
                    )
                }
            }
        }
    }
}
