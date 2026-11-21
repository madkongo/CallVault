/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** Review C60: the turns of one call must never be read as the next call's. */
class SpeakerTurnsHoldTest {

    @Test
    fun nothing_is_held_before_a_call_has_ended() {
        assertEquals("", SpeakerTurnsHold().value)
    }

    @Test
    fun a_stored_call_is_readable_after_it_ends() {
        val hold = SpeakerTurnsHold()
        hold.store("0:1500:near")
        assertEquals("0:1500:near", hold.value)
    }

    @Test
    fun a_daemon_stereo_call_then_a_handoff_call_leaves_no_turns_for_the_second() {
        val hold = SpeakerTurnsHold()
        hold.store("0:1500:near;1500:900:far")   // call 1, recorded in the daemon
        hold.beginCall()                          // call 2 starts (a handoff, which never stores turns)
        assertEquals("", hold.value)
    }

    @Test
    fun a_new_call_replaces_what_the_last_one_left() {
        val hold = SpeakerTurnsHold()
        hold.store("call one")
        hold.beginCall()
        hold.store("call two")
        assertEquals("call two", hold.value)
    }
}
