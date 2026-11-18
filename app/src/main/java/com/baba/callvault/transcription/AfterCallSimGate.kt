/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.os.SystemClock
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.calls.CallSim
import com.baba.callvault.data.calls.SimCards
import com.baba.callvault.data.calls.SimChoice
import com.baba.callvault.data.calls.SimHit
import com.baba.callvault.data.calls.SimLabels
import com.baba.callvault.data.calls.SimMatch
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.utils.AppLogger

/**
 * Applies [AfterCallSimPolicy] to one finished recording.
 *
 * The SIM comes from the call log, the same way the opt-in SIM label does, and it is read whether
 * or not that label is turned on. Android writes the call-log row only after the call ends
 * ([CallLog.Calls](https://developer.android.com/reference/android/provider/CallLog.Calls)), so this
 * waits briefly for the row of THIS call. A previous call is not used. If the row never appears,
 * the call is transcribed. A recording is never deleted from here.
 *
 * App calls are left alone: they are not in the call log, and a phone call at the same minute must
 * not lend them its SIM.
 */
object AfterCallSimGate {

    private const val TAG = "CV:AfterCallSim"

    /** A few tries while Android writes the call-log row, then give up. */
    private const val ATTEMPTS = 8
    private const val RETRY_MS = 500L

    /**
     * Skip after-call transcription for this recording.
     *
     * One SIM skips a call that is a different card. An unknown call is transcribed while that
     * card is still in the phone, and skipped when it has been removed. This never deletes a
     * recording. The file name is not logged.
     */
    fun skipTranscription(context: Context, displayName: String): Boolean {
        val prefs = AppPreferences(context)
        if (!prefs.getTranscriptionMode().transcribesOnCallEnd) return false
        val cards = SimCards.list(context, context.getString(R.string.sim_label_word))
        val raw = prefs.getTranscriptionAutoSim()
        val choice = SimMatch.stabilize(raw, cards).also { if (it != raw) prefs.setTranscriptionAutoSim(it) }
        if (choice == SimChoice.All) return false
        val hit = SimMatch.afterCall(choice, resolvedCall(context, displayName), cards)
        val skip = hit == SimHit.OTHER
        if (skip) AppLogger.i(TAG, "Not transcribing: choice=${choice.logKey()} hit=$hit")
        else if (hit == SimHit.UNKNOWN) AppLogger.i(TAG, "SIM unknown; transcribing (choice=${choice.logKey()})")
        return skip
    }

    /**
     * The call-log account behind [displayName], or null. Waits briefly for the row of this call.
     * Not a carrier recording, or a row that never appears, is null.
     */
    private fun resolvedCall(context: Context, displayName: String): CallSim? {
        if (!RecordingsRepository.isCarrierCall(displayName)) return null
        val startedAt = RecordingsRepository.parseName(displayName).startedAtMillis ?: return null
        val simWord = context.getString(R.string.sim_label_word)
        repeat(ATTEMPTS) { attempt ->
            SimLabels.forJustEnded(context, startedAt, simWord)?.let { return it }
            if (attempt < ATTEMPTS - 1) SystemClock.sleep(RETRY_MS)
        }
        return null
    }
}
