/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * The speaker turns of the recording that just ended, kept because the app asks for them after the session is gone.
 *
 * Review C60: the daemon never cleared them, so a later call that stored none (a handoff call, a scrcpy fallback, a mono
 * route) was read as having the EARLIER call's turns, which labels the wrong speakers and corrupts the learned channel
 * map. Each new call starts by [beginCall], so what is held is only ever the last call's own.
 */
internal class SpeakerTurnsHold {

    @Volatile
    var value: String = ""
        private set

    /** A new call is starting: nothing the previous one left is this one's. */
    fun beginCall() {
        value = ""
    }

    /** The turns of the call that just ended; "" when its capture produced none. */
    fun store(turns: String) {
        value = turns
    }
}
