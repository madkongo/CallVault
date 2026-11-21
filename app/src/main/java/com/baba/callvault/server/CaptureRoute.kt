/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Which capture path a carrier call took, as the first line of the capture health the app writes to its log (review
 * C131). The daemon used to log a switch to scrcpy only in its own ring, so a phone whose direct capture never worked
 * was never recognised from a report.
 */
internal object CaptureRoute {

    private const val MAX_REASON = 120

    fun direct(): String = "path=direct"

    fun scrcpyDirectUnsupported(): String = "path=scrcpy (direct capture not supported here)"

    fun scrcpyAfterDirectFailure(error: Throwable): String =
        "path=scrcpy (direct failed: ${CaptureRecoveryLog.reason(error).take(MAX_REASON)})"
}
