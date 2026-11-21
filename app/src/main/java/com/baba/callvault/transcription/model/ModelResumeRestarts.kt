/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import android.content.Context

/**
 * How many times in a row a server answered a resume with the whole file (R27).
 *
 * Kept on disk because each stop starts a fresh worker attempt: an in-memory count would always read
 * one. Its own small preferences file, so no existing pref is touched.
 */
internal object ModelResumeRestarts {
    private const val FILE = "model_download_restarts"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Adds one restart for [modelId] and returns the new consecutive count. */
    fun recordRestart(context: Context, modelId: String): Int {
        val next = prefs(context).getInt(modelId, 0) + 1
        prefs(context).edit().putInt(modelId, next).apply()
        return next
    }

    /** Forgets the count: the server continued properly, the download finished, or it was given up on. */
    fun reset(context: Context, modelId: String) {
        prefs(context).edit().remove(modelId).apply()
    }
}
