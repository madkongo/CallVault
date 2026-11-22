/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.calls.CallSim
import com.baba.callvault.data.calls.SimCard
import com.baba.callvault.data.calls.SimCards
import com.baba.callvault.data.calls.SimChoice
import com.baba.callvault.data.calls.SimHit
import com.baba.callvault.data.calls.SimLabels
import com.baba.callvault.data.calls.SimMatch
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.delay

/**
 * "Transcribe after each call" limited to one SIM (issue #81).
 *
 * Asked by [TranscriptionWorker] at the start of an after-call run, so the wait for the call-log row happens on
 * the worker's thread, never on Main or in the call-end finalisation that queued the run. The SIM comes from the
 * call log, the same way the opt-in SIM label does, and it is read whether or not that label is turned on.
 * Android writes the call-log row only after the call ends
 * ([CallLog.Calls](https://developer.android.com/reference/android/provider/CallLog.Calls)), so this waits briefly
 * for the row of THIS call. A previous call is not used.
 *
 * Anything uncertain transcribes, as "All SIMs" would: an unknown SIM, a chosen SIM that is not in the phone now
 * (eSIM re-downloaded, card swapped, settings imported from another phone), or any failure reading the SIMs or
 * the call log. Each of those is logged. A recording is never deleted from here.
 *
 * App calls are left alone: they are not in the call log, and a phone call at the same minute must not lend
 * them its SIM.
 */
object AfterCallSimGate {

    private const val TAG = "CV:AfterCallSim"

    /** A few tries while Android writes the call-log row, then give up and transcribe. */
    private const val ATTEMPTS = 8
    private const val RETRY_MS = 500L

    /** Why a call was or was not transcribed. Only [OTHER] skips. */
    internal enum class Verdict(val skip: Boolean) {
        ALL(false),
        CHOSEN_ABSENT(false),
        CHOSEN(false),
        UNKNOWN(false),
        OTHER(true),
    }

    /**
     * True when this after-call run should not transcribe [displayName]. Never throws: a failure reads as
     * "All SIMs" (transcribe) and is logged. The file name is not logged.
     */
    suspend fun skipTranscription(context: Context, displayName: String): Boolean =
        runCatching { decideFor(context, displayName) }
            .onFailure {
                if (it is kotlin.coroutines.cancellation.CancellationException) throw it
                AppLogger.w(TAG, "SIM check failed; transcribing as for all SIMs: ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrDefault(false)

    private suspend fun decideFor(context: Context, displayName: String): Boolean {
        val prefs = AppPreferences(context)
        if (!prefs.getTranscriptionMode().transcribesOnCallEnd) return false
        val raw = prefs.getTranscriptionAutoSim()
        if (raw == SimChoice.All) return false
        val cards = SimCards.list(context, context.getString(R.string.sim_label_word))
        val choice = SimMatch.stabilize(raw, cards).also { if (it != raw) prefs.setTranscriptionAutoSim(it) }
        // Checked before the call-log wait, so an absent SIM costs no wait at all.
        val call = if (chosenAbsent(choice, cards)) null else resolvedCall(context, displayName)
        val verdict = verdict(choice, cards, call)
        when (verdict) {
            Verdict.OTHER -> AppLogger.i(TAG, "Not transcribing: the call was on another SIM (choice=${choice.logKey()})")
            Verdict.CHOSEN_ABSENT -> AppLogger.i(
                TAG,
                "Chosen SIM is not in this phone (${cards.size} SIM(s) seen); transcribing (choice=${choice.logKey()})",
            )
            Verdict.UNKNOWN -> AppLogger.i(TAG, "SIM of this call unknown; transcribing (choice=${choice.logKey()})")
            Verdict.CHOSEN, Verdict.ALL -> Unit
        }
        return verdict.skip
    }

    internal fun chosenAbsent(choice: SimChoice, installed: List<SimCard>): Boolean =
        choice is SimChoice.Only && !SimMatch.present(choice, installed)

    /** The rule, without Android: [call] is the call-log account of this call, or null when it could not be told. */
    internal fun verdict(choice: SimChoice, installed: List<SimCard>, call: CallSim?): Verdict {
        if (choice == SimChoice.All) return Verdict.ALL
        if (chosenAbsent(choice, installed)) return Verdict.CHOSEN_ABSENT
        return when (SimMatch.afterCall(choice, call, installed)) {
            SimHit.CHOSEN -> Verdict.CHOSEN
            SimHit.UNKNOWN -> Verdict.UNKNOWN
            SimHit.OTHER -> Verdict.OTHER
        }
    }

    /**
     * The call-log account behind [displayName], or null. Waits briefly for the row of this call.
     * Not a carrier recording, or a row that never appears, is null.
     */
    private suspend fun resolvedCall(context: Context, displayName: String): CallSim? {
        if (!RecordingsRepository.isCarrierCall(displayName)) return null
        val startedAt = RecordingsRepository.parseName(displayName).startedAtMillis ?: return null
        val simWord = context.getString(R.string.sim_label_word)
        repeat(ATTEMPTS) { attempt ->
            SimLabels.forJustEnded(context, startedAt, simWord)?.let { return it }
            if (attempt < ATTEMPTS - 1) delay(RETRY_MS)
        }
        return null
    }
}
