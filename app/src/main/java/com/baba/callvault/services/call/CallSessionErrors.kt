/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import com.baba.callvault.system.diagnostics.CallEndLog
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Review C40: what the call-session scope does with an exception nobody caught. Without a handler it crashed the
 * app at the start or end of a call. The failure goes to the always-on log, so "why was my call not recorded" can be
 * answered from a report. Never throws.
 */
object CallSessionErrors {
    fun handler(context: Context, where: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, error ->
            // Always-on, not only the debug log: a call that went unrecorded because of this must be explainable.
            CallEndLog.record(context, "call session: unexpected failure in $where, the app was kept running: ${CallEndLog.describe(error)}")
        }
}
