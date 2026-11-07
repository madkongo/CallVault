/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.app.NotificationManager
import com.baba.callvault.data.AppPreferences.AfterCallAskStyle
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

    private val band = AfterCallPolicy.NOTIFICATION_ID_BASE until
        AfterCallPolicy.NOTIFICATION_ID_BASE + AfterCallPolicy.NOTIFICATION_ID_SPAN

    private fun facts(
        stillPending: Boolean = true,
        inCatalog: Boolean = true,
        deleteRequested: Boolean = false,
        engaged: Boolean = false,
    ) = AfterCallPolicy.HeldFacts(stillPending, inCatalog, deleteRequested, engaged)

    @Test
    fun `the first notification id starts the band`() {
        assertEquals(AfterCallPolicy.NOTIFICATION_ID_BASE, AfterCallPolicy.nextNotificationId(null))
    }

    @Test
    fun `notification ids count up and wrap inside their own band, off every fixed id`() {
        var id: Int? = null
        val seen = mutableListOf<Int>()
        repeat(AfterCallPolicy.NOTIFICATION_ID_SPAN + 5) {
            id = AfterCallPolicy.nextNotificationId(id)
            seen += id!!
        }
        assertEquals("a full band before any repeat", AfterCallPolicy.NOTIFICATION_ID_SPAN, seen.take(AfterCallPolicy.NOTIFICATION_ID_SPAN).toSet().size)
        assertEquals(AfterCallPolicy.NOTIFICATION_ID_BASE, seen[AfterCallPolicy.NOTIFICATION_ID_SPAN])
        seen.forEach {
            assertTrue("$it out of band", it in band)
            assertFalse("$it collides with a fixed id", it in fixedIds)
        }
    }

    @Test
    fun `a stored id outside the band restarts it`() {
        assertEquals(AfterCallPolicy.NOTIFICATION_ID_BASE, AfterCallPolicy.nextNotificationId(4720))
        assertEquals(AfterCallPolicy.NOTIFICATION_ID_BASE, AfterCallPolicy.nextNotificationId(-1))
    }

    @Test
    fun `the question can be shown only with permission, notifications on and the channel not blocked`() {
        assertTrue(AfterCallPolicy.canShowQuestion(true, true, NotificationManager.IMPORTANCE_HIGH))
        assertTrue("not created yet is ours to create", AfterCallPolicy.canShowQuestion(true, true, null))
        assertFalse(AfterCallPolicy.canShowQuestion(false, true, NotificationManager.IMPORTANCE_HIGH))
        assertFalse(AfterCallPolicy.canShowQuestion(true, false, NotificationManager.IMPORTANCE_HIGH))
        assertFalse(AfterCallPolicy.canShowQuestion(true, true, NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun `the pop-up opens only for the pop-up style, with the overlay permission, on an unlocked phone`() {
        assertTrue(AfterCallPolicy.shouldPopUp(AfterCallAskStyle.POPUP, canDrawOverlays = true, keyguardLocked = false))
        assertFalse(AfterCallPolicy.shouldPopUp(AfterCallAskStyle.NOTIFICATION, canDrawOverlays = true, keyguardLocked = false))
        assertFalse(AfterCallPolicy.shouldPopUp(AfterCallAskStyle.POPUP, canDrawOverlays = false, keyguardLocked = false))
        assertFalse("never over the lock screen", AfterCallPolicy.shouldPopUp(AfterCallAskStyle.POPUP, canDrawOverlays = true, keyguardLocked = true))
    }

    @Test
    fun `the pop-up ignores taps right after a card appears`() {
        assertFalse(AfterCallPolicy.acceptsTap(shownAtMs = 1_000L, nowMs = 1_000L))
        assertFalse(AfterCallPolicy.acceptsTap(shownAtMs = 1_000L, nowMs = 1_000L + AfterCallPolicy.TAP_GUARD_MS - 1))
        assertTrue(AfterCallPolicy.acceptsTap(shownAtMs = 1_000L, nowMs = 1_000L + AfterCallPolicy.TAP_GUARD_MS))
    }

    @Test
    fun `a recording is held only when asking is on and the question can be shown`() {
        assertTrue(AfterCallPolicy.shouldHold(AfterCallMode.ASK, canShowQuestion = true))
        assertFalse("an unseeable question means keep", AfterCallPolicy.shouldHold(AfterCallMode.ASK, canShowQuestion = false))
        assertFalse(AfterCallPolicy.shouldHold(AfterCallMode.KEEP, canShowQuestion = true))
    }

    @Test
    fun `the restored 24 hour check waits only for what is left, and runs at once when overdue`() {
        val day = AfterCallPolicy.UNANSWERED_DELAY_MS
        assertEquals(day, AfterCallPolicy.remainingUnansweredDelayMs(askedAt = 1_000L, now = 1_000L))
        assertEquals(day - 60_000L, AfterCallPolicy.remainingUnansweredDelayMs(askedAt = 0L, now = 60_000L))
        assertEquals(0L, AfterCallPolicy.remainingUnansweredDelayMs(askedAt = 0L, now = day * 3))
        assertEquals("a clock moved backwards never waits longer than a day", day,
            AfterCallPolicy.remainingUnansweredDelayMs(askedAt = day * 5, now = 0L))
    }

    @Test
    fun `startup re-asks unless delete was tapped or the question was swiped away`() {
        assertTrue(AfterCallPolicy.shouldRepostQuestion(deleteRequested = false, dismissed = false))
        assertFalse(AfterCallPolicy.shouldRepostQuestion(deleteRequested = true, dismissed = false))
        assertFalse(AfterCallPolicy.shouldRepostQuestion(deleteRequested = false, dismissed = true))
    }

    @Test
    fun `the 24 hour check does nothing once the question was answered`() {
        AfterCallUnanswered.entries.forEach {
            assertEquals(UnansweredAction.NOTHING, AfterCallPolicy.unansweredAction(it, facts(stillPending = false)))
        }
    }

    @Test
    fun `the 24 hour check deletes when the setting still says delete`() {
        assertEquals(UnansweredAction.DELETE, AfterCallPolicy.unansweredAction(AfterCallUnanswered.DELETE_AFTER_24H, facts()))
    }

    @Test
    fun `the 24 hour check keeps when the setting was switched to keep meanwhile`() {
        assertEquals(UnansweredAction.KEEP, AfterCallPolicy.unansweredAction(AfterCallUnanswered.KEEP, facts()))
    }

    @Test
    fun `a tapped delete is honoured even when the setting says keep`() {
        assertEquals(
            UnansweredAction.DELETE,
            AfterCallPolicy.unansweredAction(AfterCallUnanswered.KEEP, facts(deleteRequested = true, engaged = true)),
        )
    }

    @Test
    fun `a recording the user engaged with is kept rather than deleted unanswered`() {
        assertEquals(
            UnansweredAction.KEEP,
            AfterCallPolicy.unansweredAction(AfterCallUnanswered.DELETE_AFTER_24H, facts(engaged = true)),
        )
    }

    @Test
    fun `a recording that left the catalog is only released`() {
        AfterCallUnanswered.entries.forEach {
            assertEquals(
                UnansweredAction.RELEASE,
                AfterCallPolicy.unansweredAction(it, facts(inCatalog = false, deleteRequested = true)),
            )
        }
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

    @Test
    fun `the pop-up steps aside while a call rings or is under way`() {
        listOf(
            android.media.AudioManager.MODE_RINGTONE, android.media.AudioManager.MODE_IN_CALL,
            android.media.AudioManager.MODE_IN_COMMUNICATION, android.media.AudioManager.MODE_CALL_SCREENING,
        ).forEach { assertTrue("mode $it", AfterCallPolicy.isCallAudioMode(it)) }
        assertFalse(AfterCallPolicy.isCallAudioMode(android.media.AudioManager.MODE_NORMAL))
    }
}
