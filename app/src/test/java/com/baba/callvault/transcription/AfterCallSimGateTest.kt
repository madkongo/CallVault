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
import kotlinx.coroutines.test.runTest
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.transcription.AfterCallSimGate.Reason
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    private val carrierName = "20261112_143520_in_0541234567.ogg"
    private val appCallName = "20261112_143520_voip-whatsapp_0541234567.ogg"

    /** Counts every lookup, so a test can prove a path made none. */
    private class FakeSources(
        val cards: List<SimCard>,
        val callLog: Boolean = true,
        val call: CallSim? = null,
    ) : AfterCallSimGate.Sources {
        var cardReads = 0
        var callReads = 0
        override fun cards(context: Context): List<SimCard> { cardReads++; return cards }
        override fun canReadCallLog(context: Context): Boolean = callLog
        val ringTimeAsked = mutableListOf<Boolean>()
        /** When set, answers per attempt instead of [call]: (attempt index, allowRingTime) -> row. */
        var byAttempt: ((Int, Boolean) -> CallSim?)? = null
        override fun callAt(context: Context, startedAt: Long, allowRingTime: Boolean): CallSim? {
            val attempt = callReads++
            ringTimeAsked += allowRingTime
            val rule = byAttempt
            return if (rule != null) rule(attempt, allowRingTime) else call
        }
    }

    private fun oneSim(sources: FakeSources): FakeSources {
        AfterCallSimGate.sources = sources
        val prefs = AppPreferences(context)
        prefs.setTranscriptionMode(TranscriptionMode.AFTER_EACH_CALL)
        prefs.setTranscriptionAutoSim(onlyWork)
        return sources
    }

    @Test
    fun all_sims_makes_no_lookups() = runTest {
        val fake = FakeSources(both, call = CallSim("2", 1))
        AfterCallSimGate.sources = fake
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.AFTER_EACH_CALL)
        assertEquals(Reason.ALL_SIMS, AfterCallSimGate.evaluate(context, carrierName).reason)
        assertEquals(0, fake.cardReads)
        assertEquals(0, fake.callReads)
    }

    @Test
    fun no_call_log_permission_transcribes_without_waiting() = runTest {
        val fake = oneSim(FakeSources(both, callLog = false, call = CallSim("2", 1)))
        val outcome = AfterCallSimGate.evaluate(context, carrierName)
        assertEquals(Reason.NO_CALL_LOG_PERMISSION, outcome.reason)
        assertFalse(outcome.verdict.skip)
        assertEquals(0, fake.callReads)
    }

    @Test
    fun an_app_call_transcribes_without_reading_the_call_log() = runTest {
        val fake = oneSim(FakeSources(both, call = CallSim("2", 1)))
        assertEquals(Reason.APP_CALL, AfterCallSimGate.evaluate(context, appCallName).reason)
        assertEquals(0, fake.callReads)
    }

    @Test
    fun a_row_that_never_appears_transcribes_after_the_tries() = runTest {
        assertNotNull(RecordingsRepository.parseName(carrierName).startedAtMillis)
        val fake = oneSim(FakeSources(both, call = null))
        val outcome = AfterCallSimGate.evaluate(context, carrierName)
        assertEquals(Reason.NO_ROW, outcome.reason)
        assertFalse(outcome.verdict.skip)
        assertTrue(fake.callReads > 1)
    }

    @Test
    fun a_row_without_an_account_transcribes() = runTest {
        oneSim(FakeSources(both, call = CallSim(null, null)))
        val outcome = AfterCallSimGate.evaluate(context, carrierName)
        assertEquals(Reason.ROW_WITHOUT_ACCOUNT, outcome.reason)
        assertFalse(outcome.verdict.skip)
    }

    @Test
    fun an_unrecognised_account_transcribes() = runTest {
        oneSim(FakeSources(both, call = CallSim("99", null)))
        val outcome = AfterCallSimGate.evaluate(context, carrierName)
        assertEquals(Reason.UNRECOGNISED_ACCOUNT, outcome.reason)
        assertFalse(outcome.verdict.skip)
    }

    @Test
    fun a_call_positively_on_the_other_sim_is_skipped_and_the_chosen_one_is_not() = runTest {
        oneSim(FakeSources(both, call = CallSim("2", 1)))
        assertEquals(Reason.OTHER, AfterCallSimGate.evaluate(context, carrierName).reason)
        assertTrue(AfterCallSimGate.skipTranscription(context, carrierName))
        oneSim(FakeSources(both, call = CallSim("4", 2)))
        assertEquals(Reason.CHOSEN, AfterCallSimGate.evaluate(context, carrierName).reason)
    }

    @Test
    fun a_chosen_sim_not_in_the_phone_transcribes_without_reading_the_call_log() = runTest {
        val fake = oneSim(FakeSources(listOf(personal), call = CallSim("2", 1)))
        assertEquals(Reason.CHOSEN_ABSENT, AfterCallSimGate.evaluate(context, carrierName).reason)
        assertEquals(0, fake.callReads)
    }

    @Test
    fun early_tries_accept_only_a_row_near_the_recording_start_and_ring_time_only_on_the_last() = runTest {
        // Re-review #A: a previous call's row (older start, other SIM) must not decide while this call's row is late.
        val fake = oneSim(FakeSources(both))
        fake.byAttempt = { _, ringTime -> if (ringTime) CallSim("2", 1) else null }
        AfterCallSimGate.evaluate(context, carrierName)
        assertEquals(List(fake.ringTimeAsked.size - 1) { false } + true, fake.ringTimeAsked)
        assertTrue(fake.ringTimeAsked.size > 2)
    }

    @Test
    fun this_calls_row_appearing_on_a_later_try_decides() = runTest {
        val fake = oneSim(FakeSources(both))
        // The other SIM's older row is only reachable with ring time; this call's own row lands on the third try.
        fake.byAttempt = { attempt, ringTime -> when { attempt >= 2 -> CallSim("4", 2); ringTime -> CallSim("2", 1); else -> null } }
        assertEquals(Reason.CHOSEN, AfterCallSimGate.evaluate(context, carrierName).reason)
        assertEquals(3, fake.callReads)
    }

    @Test
    fun a_row_found_only_through_ring_time_never_skips() = runTest {
        // Coordinator, after re-review #A: an older row accepted only on the last try may be a previous call.
        val fake = oneSim(FakeSources(both))
        fake.byAttempt = { _, ringTime -> if (ringTime) CallSim("2", 1) else null }
        val outcome = AfterCallSimGate.evaluate(context, carrierName)
        assertEquals(Reason.RING_TIME_ONLY, outcome.reason)
        assertFalse(outcome.verdict.skip)
    }

    @Test
    fun a_row_found_only_through_ring_time_may_still_confirm_the_chosen_sim() = runTest {
        val fake = oneSim(FakeSources(both))
        fake.byAttempt = { _, ringTime -> if (ringTime) CallSim("4", 2) else null }
        assertEquals(Reason.CHOSEN, AfterCallSimGate.evaluate(context, carrierName).reason)
    }

    @Test
    fun a_near_row_on_the_last_try_still_skips_the_other_sim() = runTest {
        val fake = oneSim(FakeSources(both))
        fake.byAttempt = { attempt, _ -> if (attempt >= 7) CallSim("2", 1) else null }
        assertEquals(Reason.OTHER, AfterCallSimGate.evaluate(context, carrierName).reason)
    }

    @Test
    fun the_choice_is_not_carried_by_a_settings_export() {
        // Review: an imported sub:N would silently pick a different SIM on another phone.
        assertFalse("transcription_after_call_sim" in AppPreferences.EXPORTABLE_KEYS)
    }

    @After
    fun reset() {
        AfterCallSimGate.sources = AfterCallSimGate.AndroidSources
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
