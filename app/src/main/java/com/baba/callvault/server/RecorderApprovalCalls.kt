/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.FailureChain

/**
 * The recorder's two Wi-Fi approval binder calls, made safe: anything thrown, Errors included (a class that failed
 * to load throws ExceptionInInitializerError, then NoClassDefFoundError on every later use; rf5, 2026-11-20), is
 * logged here with its cause chain and answered as a failure the app understands, instead of crossing the binder
 * as an exception whose cause the app never sees.
 */
internal class RecorderApprovalCalls(
    private val warn: (String) -> Unit = { AppLogger.w(TAG, it) },
) {

    fun approveNetwork(call: () -> NetworkApproval): String = runCatching { call().name }.getOrElse {
        warn("Approving this Wi-Fi for Wireless debugging threw ${FailureChain.describe(it)}; answering FAILED")
        NetworkApproval.FAILED.name
    }

    fun approveKeepingSwitch(call: () -> String): String = runCatching(call).getOrElse {
        warn("Approving this Wi-Fi (keeping the switch) threw ${FailureChain.describe(it)}; answering APPROVAL_FAILED")
        KeptSwitchResult(KeptSwitchOutcome.APPROVAL_FAILED).encode()
    }

    private companion object {
        const val TAG = "CV:RecorderServer"
    }
}
