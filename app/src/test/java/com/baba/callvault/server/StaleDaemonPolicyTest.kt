/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.server.StaleDaemonPolicy.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleDaemonPolicyTest {

    private val host = "27774 app_process / com.baba.callvault.server.RecorderServer /data/app/x/base.apk "
    private val launcherShell = "27770 sh -c setsid sh -c 'exec app_process / com.baba.callvault.server.RecorderServer' & sleep 3 "

    @Test
    fun `output without the end marker is unknown, not empty`() {
        assertEquals(Probe.Unknown, StaleDaemonPolicy.parseProbe(""))
        assertEquals(Probe.Unknown, StaleDaemonPolicy.parseProbe("27774 app_process x\n"))
        assertEquals(Probe.Unknown, StaleDaemonPolicy.parseProbe(null))
    }

    @Test
    fun `a clean scan that found nothing is an empty host list`() {
        assertEquals(Probe.Hosts(emptyList()), StaleDaemonPolicy.parseProbe("cv-end\n"))
    }

    @Test
    fun `a daemon host is listed and its adb launcher shell is not`() {
        val probe = StaleDaemonPolicy.parseProbe("$host\n$launcherShell\ncv-end\n")
        assertEquals(Probe.Hosts(listOf(27774)), probe)
    }

    @Test
    fun `a file modified a moment ago is growing`() {
        assertTrue(StaleDaemonPolicy.isGrowing(ageMs = 1_000, sizeBefore = 100, sizeAfter = null))
    }

    @Test
    fun `an old file that got bigger between two reads is growing`() {
        assertTrue(StaleDaemonPolicy.isGrowing(ageMs = 20_000, sizeBefore = 100, sizeAfter = 150))
    }

    @Test
    fun `an old file of the same size is not growing, and neither is a missing one`() {
        assertFalse(StaleDaemonPolicy.isGrowing(ageMs = 60_000, sizeBefore = 100, sizeAfter = 100))
        assertFalse(StaleDaemonPolicy.isGrowing(ageMs = null, sizeBefore = null, sizeAfter = null))
    }

    @Test
    fun `a record left from an earlier call is stale and needs no second read`() {
        assertFalse(StaleDaemonPolicy.isGrowing(ageMs = StaleDaemonPolicy.STALE_SESSION_MS + 1, sizeBefore = 100, sizeAfter = 200))
    }

    @Test
    fun `a spare is withdrawn after a refusal`() {
        val memory = SpareMemory()
        assertTrue(memory.stillAllowed(0))
        memory.noteRejected()
        assertFalse(memory.stillAllowed(1))
    }

    @Test
    fun `a spare is withdrawn when no hand-over came within the patience, and a binder resets it`() {
        val memory = SpareMemory()
        memory.noteSpared(1_000)
        assertTrue(memory.stillAllowed(1_000 + SpareMemory.PATIENCE_MS - 1))
        assertFalse(memory.stillAllowed(1_000 + SpareMemory.PATIENCE_MS))
        memory.reset()
        assertTrue(memory.stillAllowed(1_000 + SpareMemory.PATIENCE_MS))
    }

    @Test
    fun `growing file, a host and a call spares the daemon`() {
        assertEquals(StaleDaemonPolicy.Action.SPARE, StaleDaemonPolicy.decide(true, Probe.Hosts(listOf(1)), inCall = true))
    }

    @Test
    fun `growing file with an unreadable scan or call state still spares`() {
        assertEquals(StaleDaemonPolicy.Action.SPARE, StaleDaemonPolicy.decide(true, Probe.Unknown, inCall = null))
    }

    @Test
    fun `an idle daemon between calls is replaced`() {
        assertEquals(StaleDaemonPolicy.Action.KILL_THEN_LAUNCH, StaleDaemonPolicy.decide(false, Probe.Hosts(listOf(1)), inCall = true))
    }

    @Test
    fun `a clean scan with no host leaves nothing to spare`() {
        assertEquals(StaleDaemonPolicy.Action.KILL_THEN_LAUNCH, StaleDaemonPolicy.decide(true, Probe.Hosts(emptyList()), inCall = true))
    }

    @Test
    fun `a growing file after the call ended is the orphan itself and is not spared`() {
        assertEquals(StaleDaemonPolicy.Action.KILL_THEN_LAUNCH, StaleDaemonPolicy.decide(true, Probe.Hosts(listOf(1)), inCall = false))
    }
}
