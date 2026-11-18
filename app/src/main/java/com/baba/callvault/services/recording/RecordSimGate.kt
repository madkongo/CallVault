/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.calls.SimCards
import com.baba.callvault.data.calls.SimChoice
import com.baba.callvault.data.calls.SimHit
import com.baba.callvault.data.calls.SimMatch
import com.baba.callvault.utils.AppLogger

/** Reads the auto-record SIM choice and the SIM of the call that just went off-hook. */
internal object RecordSimGate {

    private const val TAG = "CV:RecordSim"

    fun onCallStarting(context: Context, activeSlot: Int?): RecordSimPolicy.Decision {
        val prefs = AppPreferences(context)
        val cards = SimCards.list(context, context.getString(R.string.sim_label_word))
        val raw = prefs.getRecordAutoSim()
        val choice = SimMatch.stabilize(raw, cards).also { if (it != raw) prefs.setRecordAutoSim(it) }
        val active = activeSlot?.let { slot -> cards.find { it.slot == slot } }
        val hit = if (choice is SimChoice.Only) SimMatch.atStart(choice, active, cards) else SimHit.CHOSEN
        val decision = RecordSimPolicy.decide(choice, hit)
        if (choice != SimChoice.All) {
            AppLogger.i(TAG, "off-hook: choice=${choice.logKey()} slot=${activeSlot ?: "-"} hit=$hit → $decision")
        }
        return decision
    }

    /** The name of [slot] as the list would show it, for the recording notification. */
    fun labelOf(context: Context, slot: Int?): String? {
        if (slot == null) return null
        return runCatching {
            SimCards.list(context, context.getString(R.string.sim_label_word))
                .firstOrNull { it.slot == slot }?.label
        }.getOrNull()
    }
}
