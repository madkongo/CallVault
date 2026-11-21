/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** C96: an imported settings file does what the same toggles would have done, not just write preferences. */
class SettingsReconcileTest {

    private class Recorder {
        val calls = mutableListOf<String>()
        var voipNow = false
        fun effects() = SettingsReconcile.Effects(
            reconcileModes = { calls += "modes" },
            isVoipOn = { voipNow },
            applyVoip = { calls += "voip:$it" },
            applyTranscription = { calls += "transcription" },
            applySync = { calls += "sync" },
            applyRetention = { calls += "retention" },
            applyUpdates = { calls += "updates" },
        )
    }

    private fun run(rec: Recorder, voipBefore: Boolean) = runBlocking {
        SettingsReconcile.afterImport(voipBefore, rec.effects(), Dispatchers.Unconfined).join()
    }

    @Test
    fun `an import that turns app calls on arms them without a restart`() {
        val rec = Recorder().apply { voipNow = true }
        run(rec, voipBefore = false)
        assertEquals(1, rec.calls.count { it == "voip:true" })
    }

    @Test
    fun `an import that turns app calls off disarms them`() {
        val rec = Recorder().apply { voipNow = false }
        run(rec, voipBefore = true)
        assertEquals(1, rec.calls.count { it == "voip:false" })
    }

    @Test
    fun `an import that leaves app calls as they were does not touch detection`() {
        val rec = Recorder().apply { voipNow = true }
        run(rec, voipBefore = true)
        assertEquals(0, rec.calls.count { it.startsWith("voip") })
    }

    @Test
    fun `the mode check runs before the app-call state is read, so an impossible switch is not armed`() {
        var voipNow = true
        val calls = mutableListOf<String>()
        val effects = SettingsReconcile.Effects(
            reconcileModes = { voipNow = false; calls += "modes" }, // this phone/mode cannot do app calls
            isVoipOn = { voipNow },
            applyVoip = { calls += "voip:$it" },
            applyTranscription = {}, applySync = {}, applyRetention = {}, applyUpdates = {},
        )
        runBlocking { SettingsReconcile.afterImport(false, effects, Dispatchers.Unconfined).join() }
        assertEquals(listOf("modes"), calls)
    }

    @Test
    fun `all four schedulers are re-applied`() {
        val rec = Recorder()
        run(rec, voipBefore = false)
        assertEquals(setOf("transcription", "sync", "retention", "updates"), rec.calls.toSet() - "modes")
    }

    @Test
    fun `one failing scheduler does not stop the others`() {
        val calls = mutableListOf<String>()
        val effects = SettingsReconcile.Effects(
            reconcileModes = {}, isVoipOn = { false }, applyVoip = {},
            applyTranscription = { error("boom") },
            applySync = { calls += "sync" }, applyRetention = { calls += "retention" }, applyUpdates = { calls += "updates" },
        )
        runBlocking { SettingsReconcile.afterImport(false, effects, Dispatchers.Unconfined).join() }
        assertEquals(listOf("sync", "retention", "updates"), calls)
    }
}
