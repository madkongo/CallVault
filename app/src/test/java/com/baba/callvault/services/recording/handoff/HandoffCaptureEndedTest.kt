/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import java.io.FileDescriptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reviews C33 and C135: when the handoff capture ends for good in mid-call, the app is told at once, the recording is
 * no longer reported live, and a late delivery cannot start a second capture into the same output.
 */
class HandoffCaptureEndedTest {

    private val target = HandoffReceiver.Target(FileDescriptor(), "audio/mp4a-latm", 0, 64_000, true)

    @After
    fun reset() = HandoffReceiver.abort()

    @Test
    fun a_spent_rebuild_budget_tells_the_app_and_drops_the_target() {
        val told = mutableListOf<String>()
        HandoffReceiver.begin(target, onCaptureEnded = { told += it })

        HandoffReceiver.captureEndedForGood("invalidated", HandoffEarlyEnd.Outcome.BUDGET_SPENT, rearms = 3)

        assertEquals(1, told.size)
        assertTrue(told.single(), "invalidated" in told.single())
        assertFalse(HandoffReceiver.isLive)
        assertFalse("a late delivery must find no target to start a second capture on", HandoffReceiver.hasPendingTarget)
    }

    @Test
    fun a_failed_rebuild_does_the_same() {
        val told = mutableListOf<String>()
        HandoffReceiver.begin(target, onCaptureEnded = { told += it })

        HandoffReceiver.captureEndedForGood("invalidated", HandoffEarlyEnd.Outcome.REBUILD_FAILED, rearms = 1)

        assertEquals(1, told.size)
        assertFalse(HandoffReceiver.hasPendingTarget)
    }

    @Test
    fun a_listener_that_throws_cannot_stop_the_rest_of_the_teardown() {
        HandoffReceiver.begin(target, onCaptureEnded = { throw IllegalStateException("engine gone") })

        HandoffReceiver.captureEndedForGood("invalidated", HandoffEarlyEnd.Outcome.BUDGET_SPENT, rearms = 3)

        assertFalse(HandoffReceiver.hasPendingTarget)
        assertFalse(HandoffReceiver.isLive)
    }

    @Test
    fun without_a_listener_the_teardown_still_happens() {
        HandoffReceiver.begin(target)
        HandoffReceiver.captureEndedForGood("invalidated", HandoffEarlyEnd.Outcome.BUDGET_SPENT, rearms = 3)
        assertFalse(HandoffReceiver.hasPendingTarget)
    }

    @Test
    fun a_normal_begin_still_holds_the_target() {
        HandoffReceiver.begin(target)
        assertTrue(HandoffReceiver.hasPendingTarget)
    }
}
