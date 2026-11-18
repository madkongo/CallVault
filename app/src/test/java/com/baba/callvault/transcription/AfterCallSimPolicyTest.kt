/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.calls.SimChoice
import com.baba.callvault.data.calls.SimId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which finished call is transcribed, and which recording is deleted, when after-call
 * transcription is limited to one SIM. An unknown SIM must keep today's behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallSimPolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun reset() {
        val prefs = AppPreferences(context)
        prefs.setTranscriptionAutoSim(SimChoice.All)
        prefs.setTranscriptionOtherSim(OtherSimAction.KEEP)
        prefs.setRecordAutoSim(SimChoice.All)
    }

    @Test
    fun defaults_are_every_sim_and_keep() {
        val prefs = AppPreferences(context)
        assertEquals(SimChoice.All, prefs.getTranscriptionAutoSim())
        assertEquals(SimChoice.All, prefs.getRecordAutoSim())
        assertEquals(OtherSimAction.KEEP, prefs.getTranscriptionOtherSim())
        assertEquals("all", AppPreferences.DefaultsValue.TRANSCRIPTION_AFTER_CALL_SIM)
    }

    @Test
    fun a_stored_slot_round_trips_and_all_clears_it() {
        val prefs = AppPreferences(context)
        prefs.setTranscriptionAutoSim(SimChoice.Only(SimId.Slot(2)))
        prefs.setTranscriptionOtherSim(OtherSimAction.DELETE)
        assertEquals(SimChoice.Only(SimId.Slot(2)), AppPreferences(context).getTranscriptionAutoSim())
        // An old "delete the other SIM" value is still stored, and is ignored.
        assertEquals(OtherSimAction.DELETE, AppPreferences(context).getTranscriptionOtherSim())
        prefs.setTranscriptionAutoSim(SimChoice.All)
        assertEquals(SimChoice.All, prefs.getTranscriptionAutoSim())
        assertEquals(OtherSimAction.KEEP, OtherSimAction.fromKey("nope"))
    }

    @Test
    fun a_stored_none_is_read_as_all_sims_and_rewritten() {
        val file = context.getSharedPreferences("callvault_prefs", Context.MODE_PRIVATE)
        file.edit().putString("transcription_after_call_sim", "none").putString("record_auto_sim", "none").commit()
        val prefs = AppPreferences(context)
        assertEquals(SimChoice.All, prefs.getTranscriptionAutoSim())
        assertEquals(SimChoice.All, prefs.getRecordAutoSim())
        assertEquals("all", file.getString("transcription_after_call_sim", null))
        assertEquals("all", file.getString("record_auto_sim", null))
    }

    @Test
    fun every_sim_transcribes_and_keeps_whatever_the_call_was() {
        listOf(null, 1, 2).forEach { resolved ->
            val decision = AfterCallSimPolicy.decide(targetSlot = null, otherDeletes = true, resolvedSlot = resolved)
            assertTrue(decision.transcribe)
            assertFalse(decision.deleteRecording)
        }
    }

    @Test
    fun an_unknown_sim_is_transcribed_and_kept() {
        val decision = AfterCallSimPolicy.decide(targetSlot = 1, otherDeletes = true, resolvedSlot = null)
        assertTrue(decision.transcribe)
        assertFalse("must not delete a call whose SIM could not be resolved", decision.deleteRecording)
    }

    @Test
    fun the_chosen_sim_is_transcribed_and_kept() {
        val decision = AfterCallSimPolicy.decide(targetSlot = 1, otherDeletes = true, resolvedSlot = 1)
        assertTrue(decision.transcribe)
        assertFalse(decision.deleteRecording)
    }

    @Test
    fun the_other_sim_is_not_transcribed_and_the_recording_is_kept() {
        val keep = AfterCallSimPolicy.decide(targetSlot = 1, otherDeletes = false, resolvedSlot = 2)
        assertFalse(keep.transcribe)
        assertFalse(keep.deleteRecording)

        val storedDelete = AfterCallSimPolicy.decide(targetSlot = 2, otherDeletes = true, resolvedSlot = 1)
        assertFalse(storedDelete.transcribe)
        assertFalse("transcription settings never delete a recording", storedDelete.deleteRecording)
    }
}
