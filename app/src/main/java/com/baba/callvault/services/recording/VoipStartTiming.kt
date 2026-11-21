/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * The always-on line for how long the app and caller lookups ran before an app call's capture opened (review C108).
 *
 * Logging only. Moving them off the start is a call-path change that needs these numbers from real phones first:
 * the caller name feeds the file name, and the late lookup may not find it once the ring notice is gone.
 */
object VoipStartTiming {
    fun line(appMs: Long, callerMs: Long): String {
        val app = appMs.coerceAtLeast(0L)
        val caller = callerMs.coerceAtLeast(0L)
        return "app call start: lookups before the capture opened took ${app + caller} ms (app $app ms, caller $caller ms)"
    }
}
