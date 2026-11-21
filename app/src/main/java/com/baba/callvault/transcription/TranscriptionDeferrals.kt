/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import java.util.UUID

/**
 * How many of a work request's retries were deferrals (another run going, a hot phone, a metered network, a
 * summary) rather than real cloud failures. WorkManager's runAttemptCount counts both, and the cloud retry
 * budget ([TranscriptionRouting.mayRetryCloud]) must count only the real ones, or five deferrals would make the
 * next passing cloud error mark the recording FAILED.
 */
internal object TranscriptionDeferrals {
    private const val FILE = "cv_transcription_deferrals"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(context: Context, id: UUID) {
        val p = prefs(context)
        p.edit().putInt(id.toString(), p.getInt(id.toString(), 0) + 1).apply()
    }

    fun count(context: Context, id: UUID): Int = prefs(context).getInt(id.toString(), 0)

    fun clear(context: Context, id: UUID) = prefs(context).edit().remove(id.toString()).apply()

    /** The attempts that were real failures. */
    fun realAttempts(runAttemptCount: Int, deferrals: Int): Int = (runAttemptCount - deferrals).coerceAtLeast(0)
}
