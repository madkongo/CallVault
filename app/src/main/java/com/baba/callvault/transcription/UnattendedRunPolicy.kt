/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.os.PowerManager

/**
 * When an automatic transcription run holds back (review R10). A run the user tapped is never held:
 * they asked for it. Battery Saver is deliberately not read: someone who keeps it on all the time would
 * never be transcribed, and the low-battery constraint on the work request covers the case that matters.
 */
internal object UnattendedRunPolicy {

    /** True when an automatic run should wait because the phone is already [PowerManager.THERMAL_STATUS_SEVERE] or worse. */
    fun shouldDefer(userRequested: Boolean, thermalStatus: Int): Boolean =
        !userRequested && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
}
