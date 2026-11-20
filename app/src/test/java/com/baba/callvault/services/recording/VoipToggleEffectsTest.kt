/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Review C13: closing Settings while "Record app calls" was arming left app-call detection off. */
class VoipToggleEffectsTest {

    @Test
    fun `detection starts on the tap, before the arm has finished`() {
        val events = mutableListOf<String>()
        val armGate = CountDownLatch(1)
        val armScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val armed = VoipToggleEffects.apply(
            turnOn = true,
            persist = { events.add("persist=$it") },
            startDetection = { events.add("detection") },
            arm = { armGate.await(5, TimeUnit.SECONDS); events.add("armed"); true },
            scope = armScope,
        )

        // The arm is still blocked, and detection is already live.
        assertEquals(listOf("persist=true", "detection"), events.toList())
        armGate.countDown()
        assertTrue(runBlocking { armed.await() })
        armScope.cancel()
    }

    @Test
    fun `closing the page mid-arm cancels nothing that detection or the arm needs`() {
        val armScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val armGate = CountDownLatch(1)
        var detectionStarted = false
        var armFinished = false

        val armed = VoipToggleEffects.apply(
            turnOn = true,
            persist = {},
            startDetection = { detectionStarted = true },
            arm = { armGate.await(5, TimeUnit.SECONDS); armFinished = true; true },
            scope = armScope,
        )
        // The page follows the result for its status line, then leaves composition while arming.
        pageScope.launch { armed.await() }
        pageScope.cancel()
        armGate.countDown()

        assertTrue(runBlocking { armed.await() })
        assertTrue(detectionStarted)
        assertTrue(armFinished)
        armScope.cancel()
    }

    @Test
    fun `a failed arm is reported, and detection was started anyway`() {
        val armScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var detectionStarted = false
        val armed = VoipToggleEffects.apply(
            turnOn = true, persist = {}, startDetection = { detectionStarted = true },
            arm = { error("daemon gone") }, scope = armScope,
        )
        assertFalse(runBlocking { armed.await() })
        assertTrue(detectionStarted)
        armScope.cancel()
    }

    /** Review C19: the ring listener's approval follows the switch at once, both ways, not at the next start. */
    @Test
    fun `the ring listener is re-synced on every toggle, after the choice is saved`() {
        for (turnOn in listOf(true, false)) {
            val events = mutableListOf<String>()
            val armScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val armed = VoipToggleEffects.apply(
                turnOn = turnOn,
                persist = { events.add("persist=$it") },
                startDetection = {},
                arm = { true },
                syncRingListener = { events.add("listener") },
                scope = armScope,
            )
            runBlocking { armed.await() }
            assertEquals(listOf("persist=$turnOn", "listener"), events.toList())
            armScope.cancel()
        }
    }
}
