/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.AppPreferences.AfterCallMode
import com.baba.callvault.utils.AppLogger

/**
 * Writes "When a call ends" for the Settings row and the wizard alike, so both apply the same rule: going
 * back to Keep keeps every recording still waiting for an answer. A question the user is no longer being
 * asked must not leave its recording with no Drive copy and no transcript.
 */
object AfterCallSettings {

    private const val TAG = "CV:AfterCall"

    /** Stores [mode]; on Keep, keeps the waiting recordings on a background thread — only if there are any. */
    fun setMode(context: Context, mode: AfterCallMode) {
        val app = context.applicationContext
        AppPreferences(app).setAfterCallMode(mode)
        if (mode != AfterCallMode.KEEP || AfterCallDecisions.pendingNames(app).isEmpty()) return
        Thread { keepWaiting(app) }.apply { isDaemon = true; name = "cv-after-call-keep-all" }.start()
    }

    /** Blocking: keeps every recording still waiting. Returns how many. */
    internal fun keepWaiting(context: Context): Int = runCatching {
        AfterCallDecision.keepAll(context).also { if (it > 0) AppLogger.i(TAG, "Back to Keep: kept $it waiting recording(s)") }
    }.getOrElse {
        AppLogger.w(TAG, "Could not keep the waiting recordings: ${it.message}")
        0
    }
}
