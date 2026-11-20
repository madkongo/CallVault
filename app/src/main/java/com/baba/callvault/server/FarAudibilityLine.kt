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

    /**
     * Review N20: a call held until answered and never answered says nothing about the mix. A slice from before the
     * hold took effect may still be in the file (434 ms on the OP9, 2026-11-20); the far party is silent in it anyway.
     */
    const val HELD_TO_THE_END =
        "Far party not judged: the capture was paused or held until the end, nothing recorded after it (an app call never answered)"

    /** Nothing encoded and no pause: the capture itself never got going, which says nothing about the app or the mix. */
    const val NOTHING_RECORDED =
        "Far party was never audible: no audio reached the file at all — the capture never got going (not a sign the app blocks capture)"

    /**
     * Null when the far party was heard. [encodedFrames] is what reached the file; [pauseSeen] is whether the capture
     * was paused (by the user or an answer hold) at any point; [framesAfterResume] is what reached the file after the
     * first pause, so zero with a pause means it stayed paused to the end.
     */
    fun atEnd(farPartyHeard: Boolean, encodedFrames: Long, pauseSeen: Boolean, framesAfterResume: Long): Line? = when {
        farPartyHeard -> null
        pauseSeen && framesAfterResume == 0L -> Line(warn = false, HELD_TO_THE_END)
        encodedFrames > 0L -> Line(warn = true, NEVER_AUDIBLE)
        else -> Line(warn = true, NOTHING_RECORDED)
    }
}
