/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.calls

import android.content.Context
import com.baba.callvault.services.call.CallSessionManager

/** Process-wide holder so broadcast + Telecom sources share one router and one sink. */
object CallDetection {
    lateinit var router: CallEventRouter
        private set

    fun init(context: Context) {
        if (::router.isInitialized) return
        val mgr = CallSessionManager.getInstance(context)
        router = CallEventRouter(sink = { event -> mgr.onCallEvent(event) })
    }

    fun setMode(dialer: Boolean) {
        router.setActiveMode(if (dialer) DetectionMode.TELECOM else DetectionMode.BROADCAST)
    }
}
