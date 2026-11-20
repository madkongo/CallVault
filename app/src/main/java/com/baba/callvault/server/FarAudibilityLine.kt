/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/** What the end of a VoIP capture says about whether the far party was heard. */
internal object FarAudibilityLine {

    data class Line(val warn: Boolean, val text: String)

    const val NEVER_AUDIBLE =
        "Far party was never audible — this app blocks capture, or the OEM did not attach the call to our mix"

    /** Review N20: a call held until answered and never answered encodes nothing, which says nothing about the mix. */
    const val NOTHING_RECORDED =
        "Far party not judged: nothing was recorded (the capture was paused or suspended for the whole call)"

    /** Null when the far party was heard; [encodedFrames] is what reached the file. */
    fun atEnd(farPartyHeard: Boolean, encodedFrames: Long): Line? = when {
        farPartyHeard -> null
        encodedFrames <= 0L -> Line(warn = false, NOTHING_RECORDED)
        else -> Line(warn = true, NEVER_AUDIBLE)
    }
}
