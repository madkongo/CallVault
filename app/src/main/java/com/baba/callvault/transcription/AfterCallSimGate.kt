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
 * Asked by [TranscriptionWorker] at the start of an after-call run, before its run gate, so the wait for the
 * call-log row happens on the worker's thread: never on Main, in the call-end finalisation that queued the run,
 * or while holding the gate. The SIM comes from the
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

    /** Where the SIMs and the call-log row come from; replaced in tests. */
    internal interface Sources {
        fun cards(context: Context): List<SimCard>
        fun canReadCallLog(context: Context): Boolean
        /** This call's call-log account, or null when its row is not there (yet). One read, no wait. */
        fun callAt(context: Context, startedAt: Long, allowRingTime: Boolean): CallSim?
    }

    internal object AndroidSources : Sources {
        override fun cards(context: Context): List<SimCard> =
            SimCards.list(context, context.getString(R.string.sim_label_word))
        override fun canReadCallLog(context: Context): Boolean =
            com.baba.callvault.system.permissions.PermissionChecks.hasCallLogPermission(context)
        override fun callAt(context: Context, startedAt: Long, allowRingTime: Boolean): CallSim? =
            SimLabels.forJustEnded(context, startedAt, context.getString(R.string.sim_label_word), allowRingTime)
    }

    @androidx.annotation.VisibleForTesting
    internal var sources: Sources = AndroidSources

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
        runCatching { evaluate(context, displayName).verdict.skip }
            .onFailure {
                if (it is kotlin.coroutines.cancellation.CancellationException) throw it
                AppLogger.w(TAG, "SIM check failed; transcribing as for all SIMs: ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrDefault(false)

    /** Why the SIM rule transcribed or skipped; logged, and read by tests. */
    internal enum class Reason { ALL_SIMS, NOT_AFTER_CALL_MODE, CHOSEN_ABSENT, NO_CALL_LOG_PERMISSION, APP_CALL, NO_START_TIME, NO_ROW, ROW_WITHOUT_ACCOUNT, UNRECOGNISED_ACCOUNT, RING_TIME_ONLY, ZERO_DURATION_ROW, CHOSEN, OTHER }

    internal data class Outcome(val verdict: Verdict, val reason: Reason)

    internal suspend fun evaluate(context: Context, displayName: String): Outcome {
        val prefs = AppPreferences(context)
        if (!prefs.getTranscriptionMode().transcribesOnCallEnd) return Outcome(Verdict.ALL, Reason.NOT_AFTER_CALL_MODE)
        val raw = prefs.getTranscriptionAutoSim()
        if (raw == SimChoice.All) return Outcome(Verdict.ALL, Reason.ALL_SIMS)
        val cards = sources.cards(context)
        val choice = SimMatch.stabilize(raw, cards).also { if (it != raw) prefs.setTranscriptionAutoSim(it) }
        val outcome = outcomeFor(context, displayName, choice, cards)
        log(outcome, choice, cards.size)
        return outcome
    }

    /** Checked in order of cost: nothing that can be told without the call log waits for it. */
    private suspend fun outcomeFor(context: Context, displayName: String, choice: SimChoice, cards: List<SimCard>): Outcome {
        if (chosenAbsent(choice, cards)) return Outcome(Verdict.CHOSEN_ABSENT, Reason.CHOSEN_ABSENT)
        if (!RecordingsRepository.isCarrierCall(displayName)) return Outcome(Verdict.UNKNOWN, Reason.APP_CALL)
        val startedAt = RecordingsRepository.parseName(displayName).startedAtMillis
            ?: return Outcome(Verdict.UNKNOWN, Reason.NO_START_TIME)
        if (!sources.canReadCallLog(context)) return Outcome(Verdict.UNKNOWN, Reason.NO_CALL_LOG_PERMISSION)
        val (call, ringTimeOnly) = waitForRow(context, startedAt) ?: return Outcome(Verdict.UNKNOWN, Reason.NO_ROW)
        if (call.accountId == null && call.slot == null) return Outcome(Verdict.UNKNOWN, Reason.ROW_WITHOUT_ACCOUNT)
        if (!SimMatch.recognised(call, cards)) return Outcome(Verdict.UNKNOWN, Reason.UNRECOGNISED_ACCOUNT)
        return when (verdict(choice, cards, call)) {
            // A row found only through the ring-time allowance may be a previous call: it can confirm, never skip.
            // A row with duration 0 was never answered: likely a dial cancelled and redialled on the other SIM, found
            // before this call's own row is written. It can confirm, never skip.
            Verdict.OTHER -> when {
                ringTimeOnly -> Outcome(Verdict.UNKNOWN, Reason.RING_TIME_ONLY)
                call.durationSeconds == 0L -> Outcome(Verdict.UNKNOWN, Reason.ZERO_DURATION_ROW)
                else -> Outcome(Verdict.OTHER, Reason.OTHER)
            }
            else -> Outcome(Verdict.CHOSEN, Reason.CHOSEN)
        }
    }

    private fun log(outcome: Outcome, choice: SimChoice, cardCount: Int) {
        val key = choice.logKey()
        val line = when (outcome.reason) {
            Reason.OTHER -> "Not transcribing: the call was on another SIM (choice=$key)"
            Reason.CHOSEN -> "Transcribing: the call was on the chosen SIM (choice=$key)"
            Reason.CHOSEN_ABSENT -> "Chosen SIM is not in this phone ($cardCount SIM(s) seen); transcribing (choice=$key)"
            Reason.APP_CALL -> "App call, not in the call log; transcribing (choice=$key)"
            Reason.NO_START_TIME -> "No start time in the file name; transcribing (choice=$key)"
            Reason.NO_CALL_LOG_PERMISSION -> "No call-log permission, so the SIM cannot be told; transcribing (choice=$key)"
            Reason.NO_ROW -> "No call-log row for this call after $ATTEMPTS tries; transcribing (choice=$key)"
            Reason.ROW_WITHOUT_ACCOUNT -> "Call-log row has no phone account; transcribing (choice=$key)"
            Reason.UNRECOGNISED_ACCOUNT -> "Call-log account matches no SIM in this phone; transcribing (choice=$key)"
            Reason.ZERO_DURATION_ROW -> "Call-log row has duration 0 (maybe a cancelled dial); not skipping on it, transcribing (choice=$key)"
            Reason.RING_TIME_ONLY -> "Only an older ring-time row found; not skipping on a guess, transcribing (choice=$key)"
            Reason.ALL_SIMS, Reason.NOT_AFTER_CALL_MODE -> return
        }
        AppLogger.i(TAG, line)
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
     * This call's call-log account, waiting briefly while Android writes the row, and whether it was found only
     * through the ring-time allowance (start more than 25 s before the recording). Every try asks for a row near the
     * recording start; only the last also accepts an older ring-time row, because until this call's own row is
     * written an older row may be a previous call on another SIM (re-review #A). Null when no row is found.
     */
    private suspend fun waitForRow(context: Context, startedAt: Long): Pair<CallSim, Boolean>? {
        repeat(ATTEMPTS) { attempt ->
            sources.callAt(context, startedAt, allowRingTime = false)?.let { return it to false }
            if (attempt == ATTEMPTS - 1) return sources.callAt(context, startedAt, allowRingTime = true)?.let { it to true }
            delay(RETRY_MS)
        }
        return null
    }
}
