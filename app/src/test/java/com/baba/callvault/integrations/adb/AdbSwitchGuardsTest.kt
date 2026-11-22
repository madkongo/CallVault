/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSwitchGuardsTest {

    // ---- C35
    @Test
    fun `the toggle may not restart adbd while a call is being recorded`() {
        assertFalse(OfflineToggleGuard.mayRestartAdbd(recordingLive = true))
    }

    @Test
    fun `the toggle may restart adbd when nothing is recording`() {
        assertTrue(OfflineToggleGuard.mayRestartAdbd(recordingLive = false))
    }

    // ---- C56
    @Test
    fun `a failed enable leaves the opt-in off when it was off`() {
        LoopbackArm.values().filter { it != LoopbackArm.ARMED }.forEach {
            assertFalse("$it must not leave the opt-in on", OfflineToggleGuard.optInAfterEnable(wasOn = false, result = it))
        }
    }

    @Test
    fun `an armed enable keeps the opt-in on`() {
        assertTrue(OfflineToggleGuard.optInAfterEnable(wasOn = false, result = LoopbackArm.ARMED))
    }

    @Test
    fun `a failed enable never turns off an opt-in that was already on`() {
        assertTrue(OfflineToggleGuard.optInAfterEnable(wasOn = true, result = LoopbackArm.PORT_DID_NOT_COME_UP))
    }

    // ---- C99
    @Test
    fun `a flag saying the user turned it off is stale when it reads on`() {
        assertTrue(StaleWdOffFlag.shouldClear(flagSet = true, wirelessDebuggingReadsOn = true))
    }

    @Test
    fun `a flag is kept while the switch really reads off`() {
        assertFalse(StaleWdOffFlag.shouldClear(flagSet = true, wirelessDebuggingReadsOn = false))
    }

    @Test
    fun `nothing to clear when the flag is not set`() {
        assertFalse(StaleWdOffFlag.shouldClear(flagSet = false, wirelessDebuggingReadsOn = true))
        assertEquals(false, StaleWdOffFlag.shouldClear(flagSet = false, wirelessDebuggingReadsOn = false))
    }
}

/** The C56 ordering in OfflineRecording, with fakes for the preference and the ADB work. */
class OfflineRecordingOrderTest {

    @Test
    fun `a failed arm puts the opt-in back off`() {
        val writes = mutableListOf<Boolean>()
        val r = OfflineRecording.enableWithOptIn(false, { writes += it }) { LoopbackArm.PORT_DID_NOT_COME_UP }
        assertEquals(LoopbackArm.PORT_DID_NOT_COME_UP, r)
        assertEquals(listOf(true, false), writes)
    }

    @Test
    fun `the opt-in is on while arming and stays on when armed`() {
        val writes = mutableListOf<Boolean>()
        var onDuringArm = false
        OfflineRecording.enableWithOptIn(false, { writes += it }) { onDuringArm = writes.last(); LoopbackArm.ARMED }
        assertTrue(onDuringArm)
        assertEquals(true, writes.last())
    }

    @Test
    fun `an arm that throws still puts the opt-in back and rethrows`() {
        val writes = mutableListOf<Boolean>()
        var thrown = false
        try {
            OfflineRecording.enableWithOptIn(false, { writes += it }) { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertEquals(false, writes.last())
    }

    @Test
    fun `disable disarms before clearing the opt-in and clears even if disarm throws`() {
        val order = mutableListOf<String>()
        try {
            OfflineRecording.disableInOrder(true, { order += "disarm"; throw RuntimeException("x") }, { order += "skip" }) { order += "clear" }
        } catch (_: RuntimeException) {}
        assertEquals(listOf("disarm", "clear"), order)
    }

    @Test
    fun `disable while recording skips the disarm but still clears`() {
        val order = mutableListOf<String>()
        OfflineRecording.disableInOrder(false, { order += "disarm" }, { order += "skip" }) { order += "clear" }
        assertEquals(listOf("skip", "clear"), order)
    }

    @Test
    fun `disable says the listener was left open when a call is being recorded`() {
        assertEquals(false, OfflineRecording.disableInOrder(false, { }, { }) { })
    }

    @Test
    fun `disable says it closed the listener when nothing is recording`() {
        assertEquals(true, OfflineRecording.disableInOrder(true, { }, { }) { })
    }

    @Test
    fun `the left-open message needs a skipped disarm and a listener that is really open`() {
        assertEquals(true, OfflineRecording.isLeftOpen(mayDisarm = false, ownListenerOpen = true))
        assertEquals(false, OfflineRecording.isLeftOpen(mayDisarm = false, ownListenerOpen = false))
        assertEquals(false, OfflineRecording.isLeftOpen(mayDisarm = true, ownListenerOpen = true))
    }
}
