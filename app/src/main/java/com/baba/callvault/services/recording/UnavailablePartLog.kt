/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.system.diagnostics.CallEndLog
import java.util.concurrent.ConcurrentHashMap

/**
 * The always-on line for a part a service went on without (see [buildOptionalPart]), written once per process.
 *
 * AppLogger's line only reaches a report when debug logging is on. A keep-alive running without app-call detection is
 * a failure the user never sees otherwise, so every debug report must carry it, logging on or off.
 */
internal object UnavailablePartLog {

    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "app-call detection unavailable: NoClassDefFoundError: ...": the error's class and message, never a stack. */
    fun line(what: String, failure: Throwable): String =
        "${what.lowercase()} unavailable: ${CallEndLog.describe(failure)}"

    /** Hands [write] the line for [what], the first time [what] fails in this process only. */
    fun recordOnce(what: String, failure: Throwable, write: (String) -> Unit) {
        if (reported.add(what)) write(line(what, failure))
    }
}
