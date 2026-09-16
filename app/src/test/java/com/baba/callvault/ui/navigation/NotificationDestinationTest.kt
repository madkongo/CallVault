/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

import com.baba.callvault.ui.navigation.NotificationDestination.Companion.fromIntentExtra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Where a notification tap asks to go.
 *
 * The decision is tested, not the navigation: an Intent extra is the only thing crossing the
 * process boundary, and it can be minted by a build older than the one reading it, lost entirely, or
 * handed back by the recents list long after the tap it came from.
 */
class NotificationDestinationTest {

    @Test
    fun `each notification asks for its own destination`() {
        assertEquals(NotificationDestination.Status, fromIntentExtra("status", false))
        assertEquals(NotificationDestination.Update, fromIntentExtra("update", false))
        assertEquals(NotificationDestination.Pairing, fromIntentExtra("pairing", false))
        assertEquals(NotificationDestination.Debug, fromIntentExtra("debug", false))
        assertEquals(NotificationDestination.Transcript, fromIntentExtra("transcript", false))
        assertEquals(
            NotificationDestination.TranscriptFailed,
            fromIntentExtra("transcript_failed", false)
        )
    }

    @Test
    fun `a finished transcript and a failed one are told apart`() {
        // They are two notifications and a user may have both waiting. Sharing a destination would
        // mean sharing a request code, which is one PendingIntent — so whichever posted last would
        // decide where both of them led, and the failure is the half that needs acting on.
        assertNotEquals(
            fromIntentExtra("transcript", false),
            fromIntentExtra("transcript_failed", false)
        )
    }

    @Test
    fun `an intent carrying no destination asks for nothing`() {
        // The launcher icon, and every PendingIntent minted before this existed.
        assertEquals(NotificationDestination.None, fromIntentExtra(null, false))
    }

    @Test
    fun `a destination this build does not know asks for nothing`() {
        // A PendingIntent left in the shade by a newer build, naming a section this one lacks.
        // Landing nowhere in particular beats guessing.
        assertEquals(NotificationDestination.None, fromIntentExtra("summaries", false))
        assertEquals(NotificationDestination.None, fromIntentExtra("", false))
    }

    @Test
    fun `reopening from recents is not a fresh tap`() {
        // Android hands back the Intent the task was started with, extras and all. Without this,
        // one tap on "recording is broken" would re-navigate on every later return to the app.
        assertEquals(NotificationDestination.Status, fromIntentExtra("status", false))
        assertEquals(NotificationDestination.None, fromIntentExtra("status", true))
    }

    @Test
    fun `no two destinations share a request code`() {
        // PendingIntent identity ignores extras, so a shared request code makes two notifications
        // one object and lets the last one posted decide where both of them lead. Two of the four
        // call sites shipped with request code 0 before these were allocated here.
        val codes = NotificationDestination.entries.map { it.requestCode }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `no two destinations share a key`() {
        // The keys are what cross the process boundary; a duplicate would make one unreachable.
        val keys = NotificationDestination.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
