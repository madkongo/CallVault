/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import java.net.URI

/**
 * Where a call is written out: on this phone, or by a speech service the user holds a key for
 * (issue #48, "transcribe with your own key").
 *
 * The services are presets we chose and checked against their documentation; the user only pastes a
 * key. [CUSTOM] is the escape hatch for anything else that speaks the OpenAI transcription API.
 */
enum class TranscriptionService(val key: String, val preset: CloudPreset?) {
    /** whisper.cpp on this phone. The default, and the only choice that sends nothing anywhere. */
    ON_DEVICE("on_device", null),

    /**
     * Groq's hosted whisper-large-v3-turbo. Recommended: verbose_json gives segment timings, and it is
     * the same model family the phone runs, so a transcript reads the same either way.
     * Checked against https://console.groq.com/docs/speech-to-text on 2026-10-29.
     */
    GROQ(
        "groq",
        CloudPreset(
            displayName = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "whisper-large-v3-turbo",
            keyPageUrl = "https://console.groq.com/keys",
        ),
    ),

    /**
     * OpenRouter's transcription endpoint with Microsoft MAI-Transcribe 2. Its docs show this model
     * returning `segments` under verbose_json (https://openrouter.ai/docs/guides/overview/multimodal/stt.md,
     * read 2026-10-29); models that cannot are refused there with a 400, which [CloudError] reports.
     */
    OPENROUTER(
        "openrouter",
        CloudPreset(
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "microsoft/mai-transcribe-2",
            keyPageUrl = "https://openrouter.ai/keys",
        ),
    ),

    /** Any OpenAI-compatible server: the user gives the address, the model and the key. */
    CUSTOM("custom", null);

    val isCloud: Boolean get() = this != ON_DEVICE

    companion object {
        val DEFAULT = ON_DEVICE

        /** The recommended cloud option, shown with a chip. */
        val RECOMMENDED = GROQ

        /** Unknown or missing keys read as [ON_DEVICE]: never send audio on the strength of a typo. */
        fun fromKey(key: String?): TranscriptionService = entries.firstOrNull { it.key == key } ?: ON_DEVICE
    }
}

/** A service we set up for the user: where it lives, which model, and where to get a key. */
data class CloudPreset(
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val keyPageUrl: String,
)

/**
 * Everything one request needs except the key, which never lives in an object that might be logged.
 *
 * @param label the service's name as the user sees it, in messages and in the privacy note.
 */
data class CloudEndpoint(
    val service: TranscriptionService,
    val label: String,
    val baseUrl: String,
    val model: String,
) {
    /** The OpenAI-compatible transcription route under [baseUrl]. */
    val transcriptionsUrl: String get() = baseUrl.trimEnd('/') + "/audio/transcriptions"

    /**
     * What the transcript records as its "model", so it is visible which engine wrote it — e.g.
     * `cloud:groq/whisper-large-v3-turbo`. Stored where the on-device model id is stored today.
     */
    val engineId: String get() = "$ENGINE_PREFIX${service.key}/$model"

    companion object {
        const val ENGINE_PREFIX = "cloud:"

        /** Whether a stored transcript model id names a cloud engine rather than an on-device model. */
        fun isCloudEngineId(modelId: String?): Boolean = modelId?.startsWith(ENGINE_PREFIX) == true

        /**
         * The endpoint for [service], or null when there is none to call: on-device, or a Custom
         * setup whose address or model is missing or not acceptable — see [validCustomBaseUrl].
         */
        fun resolve(
            service: TranscriptionService,
            customBaseUrl: String?,
            customModel: String?,
            customLabel: String = "Custom",
        ): CloudEndpoint? {
            service.preset?.let { return CloudEndpoint(service, it.displayName, it.baseUrl, it.model) }
            if (service != TranscriptionService.CUSTOM) return null
            val base = validCustomBaseUrl(customBaseUrl) ?: return null
            val model = customModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return CloudEndpoint(service, customLabel, base, model)
        }

        /**
         * [raw] trimmed and without a trailing slash, or null unless it is an https URL with a host.
         *
         * HTTPS only: the request carries the user's key and their call audio, and Android blocks
         * cleartext for this app anyway — refusing here says so instead of failing obscurely later.
         */
        fun validCustomBaseUrl(raw: String?): String? {
            val trimmed = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.host.isNullOrBlank()) return null
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
            return trimmed
        }
    }
}
