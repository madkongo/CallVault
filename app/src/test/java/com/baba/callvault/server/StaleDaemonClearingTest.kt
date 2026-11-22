/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.server.StaleDaemonClearing.Env
import com.baba.callvault.server.StaleDaemonClearing.Staging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleDaemonClearingTest {

    private class FakeEnv(
        val growing: Boolean,
        val inCallValue: Boolean? = true,
        val probe: String? = "27774 app_process / com.baba.callvault.server.RecorderServer \ncv-end\n",
    ) : Env {
        var kills = 0
        var probes = 0
        val lines = mutableListOf<String>()
        override fun staging() = Staging("/s/x.ogg", growing, "detail")
        override fun inCall() = inCallValue
        override fun probeOutput(): String? { probes++; return probe }
        override fun kill() { kills++ }
        override fun log(line: String) { lines += line }
    }

    @Test
    fun `a recording daemon is spared, nothing is killed and the caller must not launch`() {
        val env = FakeEnv(growing = true)
        assertFalse(StaleDaemonClearing.clear(env))
        assertEquals(0, env.kills)
        assertTrue(env.lines.any { it.contains("27774") && it.contains("not killing") })
    }

    @Test
    fun `an idle daemon is killed and the caller may launch`() {
        val env = FakeEnv(growing = false)
        assertTrue(StaleDaemonClearing.clear(env))
        assertEquals(1, env.kills)
        assertEquals("no scan is needed when nothing is being recorded", 0, env.probes)
    }

    @Test
    fun `a growing file but no daemon found is killed-and-launched, never spared`() {
        val env = FakeEnv(growing = true, probe = "cv-end\n")
        assertTrue(StaleDaemonClearing.clear(env))
        assertEquals(1, env.kills)
    }

    @Test
    fun `a failed scan during a growing session spares and says so`() {
        val env = FakeEnv(growing = true, probe = null)
        assertFalse(StaleDaemonClearing.clear(env))
        assertEquals(0, env.kills)
        assertTrue(env.lines.any { it.contains("unknown") })
    }

    @Test
    fun `an unreadable call state is logged`() {
        val env = FakeEnv(growing = true, inCallValue = null)
        StaleDaemonClearing.clear(env)
        assertTrue(env.lines.any { it.contains("call state unreadable") })
    }

    @Test
    fun `the kill branch is logged with the call state`() {
        val env = FakeEnv(growing = false, inCallValue = false)
        StaleDaemonClearing.clear(env)
        assertTrue(env.lines.any { it.contains("killing") && it.contains("inCall=false") })
    }
}
