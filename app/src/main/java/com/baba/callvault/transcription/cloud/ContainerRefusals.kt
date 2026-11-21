/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import android.content.SharedPreferences

/**
 * Remembers which cloud endpoint refused the recording's own container (Ogg or M4A) so the next recording goes
 * straight to WAV instead of uploading the whole file first and being refused again (review 2026-11-20, R21).
 *
 * Keyed by address and model: changing either one is a new service and starts clean. A refusal also expires after
 * [EXPIRY_MS], so a service that later learns Ogg is tried again. Lives in its own small preferences file.
 */
class ContainerRefusals(
    private val prefs: SharedPreferences,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    fun isRefused(endpoint: CloudEndpoint): Boolean {
        val since = prefs.getLong(keyOf(endpoint), 0L)
        return since > 0L && nowMs() - since in 0..EXPIRY_MS
    }

    fun markRefused(endpoint: CloudEndpoint) {
        prefs.edit().putLong(keyOf(endpoint), nowMs()).apply()
    }

    /** The recording's own container was accepted: one unrelated 400 must not keep this service on WAV for a week. */
    fun noteContainerWorked(endpoint: CloudEndpoint) {
        if (prefs.contains(keyOf(endpoint))) clear(endpoint)
    }

    fun clear(endpoint: CloudEndpoint) {
        prefs.edit().remove(keyOf(endpoint)).apply()
    }

    private fun keyOf(endpoint: CloudEndpoint): String = "${endpoint.transcriptionsUrl}|${endpoint.model}"

    companion object {
        const val PREFS_NAME = "cloud_container_refusals"
        const val EXPIRY_MS = 7L * 24 * 60 * 60 * 1000
    }
}
