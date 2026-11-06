/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import com.baba.callvault.data.AppPreferences.AfterCallMode
import com.baba.callvault.data.AppPreferences.AfterCallUnanswered
import com.baba.callvault.services.recording.aftercall.AfterCallPolicy.UnansweredAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterCallPolicyTest {

    /** Every fixed notification id CallVault posts; a question must never land on one of them. */
    private val fixedIds = setOf(1, 2, 3, 5, 42, 43, 4242) + (4712..4724)

    @Test
    fun `asks only when the mode is ask`() {
        assertTrue(AfterCallPolicy.shouldAsk(AfterCallMode.ASK))
        assertFalse(AfterCallPolicy.shouldAsk(AfterCallMode.KEEP))
    }

    @Test
    fun `the notification id is stable for a name`() {
        val name = "20261106_101500_in_0501234567.ogg"
        assertEquals(AfterCallPolicy.notificationIdFor(name), AfterCallPolicy.notificationIdFor(name))
    }

    @Test
    fun `notification ids stay inside their own band and off every fixed id`() {
        val names = (0 until 5_000).map { "2026110${it % 9}_${it}_voip_WhatsApp_name$it.m4a" } +
            listOf("", "x", "דנה.ogg")
        names.forEach { name ->
            val id = AfterCallPolicy.notificationIdFor(name)
            assertTrue("$id out of band", id in AfterCallPolicy.NOTIFICATION_ID_BASE until
                AfterCallPolicy.NOTIFICATION_ID_BASE + AfterCallPolicy.NOTIFICATION_ID_SPAN)
            assertFalse("$id collides with a fixed id", id in fixedIds)
        }
    }

    @Test
    fun `a negative hash still maps into the band`() {
        // "polygenelubricants".hashCode() == Int.MIN_VALUE: the case plain % gets wrong.
        val id = AfterCallPolicy.notificationIdFor("polygenelubricants")
        assertTrue(id >= AfterCallPolicy.NOTIFICATION_ID_BASE)
    }

    @Test
    fun `the 24 hour check does nothing once the question was answered`() {
        AfterCallUnanswered.entries.forEach {
            assertEquals(UnansweredAction.NOTHING, AfterCallPolicy.unansweredAction(it, stillPending = false))
        }
    }

    @Test
    fun `the 24 hour check deletes when the setting still says delete`() {
        assertEquals(
            UnansweredAction.DELETE,
            AfterCallPolicy.unansweredAction(AfterCallUnanswered.DELETE_AFTER_24H, stillPending = true),
        )
    }

    @Test
    fun `the 24 hour check keeps when the setting was switched to keep meanwhile`() {
        assertEquals(
            UnansweredAction.KEEP,
            AfterCallPolicy.unansweredAction(AfterCallUnanswered.KEEP, stillPending = true),
        )
    }

    @Test
    fun `a held recording's audio and sidecars are all held`() {
        val held = setOf("20261106_101500_in_0501234567.ogg")
        assertTrue(AfterCallPolicy.isHeldFile("20261106_101500_in_0501234567.ogg", held))
        assertTrue(AfterCallPolicy.isHeldFile("20261106_101500_in_0501234567.json", held))
        assertTrue(AfterCallPolicy.isHeldFile("20261106_101500_in_0501234567.md", held))
    }

    @Test
    fun `other recordings are not held`() {
        val held = setOf("20261106_101500_in_0501234567.ogg")
        assertFalse(AfterCallPolicy.isHeldFile("20261106_101501_in_0501234567.ogg", held))
        assertFalse(AfterCallPolicy.isHeldFile("20261106_101500_in_0501234567.ogg", emptySet()))
    }

    @Test
    fun `duration formats as minutes and seconds, hours when needed`() {
        assertEquals("0:05", AfterCallPolicy.formatDuration(5_400L))
        assertEquals("4:07", AfterCallPolicy.formatDuration(247_000L))
        assertEquals("1:02:03", AfterCallPolicy.formatDuration(3_723_000L))
    }

    @Test
    fun `an unknown duration is omitted`() {
        assertNull(AfterCallPolicy.formatDuration(0L))
        assertNull(AfterCallPolicy.formatDuration(-1L))
    }
}
