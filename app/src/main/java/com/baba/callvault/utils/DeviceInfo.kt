/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import android.os.Build

/** Device-family checks that gate device-specific behaviour or user hints. */
object DeviceInfo {

    /**
     * True on vivo and iQOO phones (iQOO is a vivo sub-brand and reports `Build.MANUFACTURER = "vivo"`).
     *
     * Used to tailor the one-sided-app-call hint: on vivo the far side is silenced by the phone until the
     * user turns on vivo's own "In-app call recording" (Recorder app), so vivo users get a message that
     * points them to that toggle instead of the generic "the other app blocks capture" wording.
     *
     * [manufacturer] is a parameter so the mapping is unit-testable without touching [Build].
     */
    fun isVivo(manufacturer: String = Build.MANUFACTURER): Boolean =
        manufacturer.equals("vivo", ignoreCase = true)
}
