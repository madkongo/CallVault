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
import com.baba.callvault.data.TranscriptionMode
import com.baba.callvault.data.calls.CallSim
import com.baba.callvault.data.calls.SimCard
import com.baba.callvault.data.calls.SimChoice
import com.baba.callvault.data.calls.SimId
import com.baba.callvault.transcription.AfterCallSimGate.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After-call transcription limited to one SIM (issue #81, from PR #85). Only a call known to be on another SIM
 * is skipped; anything uncertain transcribes, as "All SIMs" would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallSimGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val work = SimCard(label = "Work", slot = 2, subId = 4, iccId = null, accountId = "4")
    private val personal = SimCard(label = "Personal", slot = 1, subId = 2, iccId = null, accountId = "2")
    private val both = listOf(personal, work)
    private val onlyWork = SimChoice.Only(SimId.Sub(4))

    @After
    fun reset() {
        AppPreferences(context).setTranscriptionAutoSim(SimChoice.All)
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.MANUAL)
    }

    @Test
    fun all_sims_transcribes_every_call() {
        assertEquals(Verdict.ALL, AfterCallSimGate.verdict(SimChoice.All, both, CallSim("2", 1)))
    }

    @Test
    fun the_chosen_sim_is_transcribed_and_the_other_is_skipped() {
        assertEquals(Verdict.CHOSEN, AfterCallSimGate.verdict(onlyWork, both, CallSim("4", 2)))
        assertEquals(Verdict.OTHER, AfterCallSimGate.verdict(onlyWork, both, CallSim("2", 1)))
        assertEquals(true, Verdict.OTHER.skip)
    }

    @Test
    fun an_unknown_sim_is_transcribed() {
        assertEquals(Verdict.UNKNOWN, AfterCallSimGate.verdict(onlyWork, both, null))
        assertFalse(Verdict.UNKNOWN.skip)
    }

    @Test
    fun a_chosen_sim_that_is_not_in_the_phone_is_transcribed_even_for_a_known_other_call() {
        // eSIM re-downloaded with a new subscription id, card swapped, or settings imported from another phone.
        assertEquals(Verdict.CHOSEN_ABSENT, AfterCallSimGate.verdict(onlyWork, listOf(personal), CallSim("2", 1)))
        assertEquals(Verdict.CHOSEN_ABSENT, AfterCallSimGate.verdict(onlyWork, emptyList(), null))
        assertFalse(Verdict.CHOSEN_ABSENT.skip)
    }

    @Test
    fun only_other_skips() {
        assertEquals(listOf(Verdict.OTHER), Verdict.entries.filter { it.skip })
    }

    @Test
    fun with_no_sims_readable_a_chosen_sim_transcribes() {
        // Robolectric has no phone-state permission, so SimCards.list is empty: the chosen SIM reads as absent.
        val prefs = AppPreferences(context)
        prefs.setTranscriptionMode(TranscriptionMode.AFTER_EACH_CALL)
        prefs.setTranscriptionAutoSim(onlyWork)
        assertFalse(runBlocking { AfterCallSimGate.skipTranscription(context, "anything.m4a") })
    }

    @Test
    fun the_default_is_all_sims_and_a_choice_round_trips() {
        val prefs = AppPreferences(context)
        assertEquals(SimChoice.All, prefs.getTranscriptionAutoSim())
        assertEquals("all", AppPreferences.DefaultsValue.TRANSCRIPTION_AFTER_CALL_SIM)
        prefs.setTranscriptionAutoSim(SimChoice.Only(SimId.Slot(2)))
        assertEquals(SimChoice.Only(SimId.Slot(2)), AppPreferences(context).getTranscriptionAutoSim())
        prefs.setTranscriptionAutoSim(SimChoice.All)
        assertEquals(SimChoice.All, prefs.getTranscriptionAutoSim())
    }
}
