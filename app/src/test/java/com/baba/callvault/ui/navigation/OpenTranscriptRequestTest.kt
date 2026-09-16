/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

import com.baba.callvault.ui.navigation.OpenTranscriptRequest.fromIntentExtra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which transcript a "transcript ready" tap asks for.
 *
 * The decision is tested, not the navigation: an Intent extra is the only thing crossing the process
 * boundary, and Android hands it back on every return from the recents list whether or not the user
 * tapped anything.
 */
class OpenTranscriptRequestTest {

    @Test
    fun `a named transcript is asked for`() {
        assertEquals("20260916_101010.123+0300_import_note.opus", fromIntentExtra("20260916_101010.123+0300_import_note.opus", false))
    }

    @Test
    fun `an intent carrying no name asks for nothing`() {
        assertNull(fromIntentExtra(null, false))
    }

    @Test
    fun `a blank name is nothing rather than a name`() {
        // It would open a reading view on a row that cannot exist, which reads as the tap having
        // opened the wrong thing rather than as nothing having been asked for.
        assertNull(fromIntentExtra("", false))
        assertNull(fromIntentExtra("   ", false))
    }

    @Test
    fun `a relaunch from recents is not a fresh request`() {
        // Android re-delivers the task's original Intent, extras and all. Without this one tap on
        // "Transcript ready" would re-open that transcript on every later return to the app, days
        // later, over whatever the user was actually doing.
        assertEquals("a.ogg", fromIntentExtra("a.ogg", false))
        assertNull(fromIntentExtra("a.ogg", true))
    }

    @Test
    fun `it does not share an extra with the recording request`() {
        // The two open different screens — a recording's own screen and the reading view — so one
        // extra read two ways would need the notification's destination threaded all the way down
        // to Home before either could be acted on.
        assert(OpenTranscriptRequest.EXTRA != OpenRecordingRequest.EXTRA)
    }
}
