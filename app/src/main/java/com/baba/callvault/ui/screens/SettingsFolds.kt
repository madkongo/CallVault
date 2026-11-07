/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import com.baba.callvault.data.AppPreferences

/**
 * The Recording rows that show only while a switch is on, and which switch brings each in. Shared by the rows
 * (a search that finds a folded row shows its switch instead, see [SearchFiltered]) and by the group's match
 * count, so "1 match" always counts what is on the screen.
 */
internal object SettingsFolds {

    /** Where the three switches stand. */
    data class Switches(val isPhoneOn: Boolean, val isAppOn: Boolean, val isAsking: Boolean) {
        companion object {
            fun of(prefs: AppPreferences) = Switches(
                isPhoneOn = prefs.isCarrierRecordingEnabled(),
                isAppOn = prefs.isVoipRecordingEnabled(),
                isAsking = prefs.getAfterCallMode() == AppPreferences.AfterCallMode.ASK,
            )
        }
    }

    const val PHONE_SWITCH = "CarrierRecordingRow"
    const val APP_SWITCH = "VoipRecordingSwitch"
    const val ASK_SWITCH = "AfterCallModeRow"
    private const val RECORD_FROM_ANSWER = "RecordFromAnswerRow"

    private val PHONE_ONLY = setOf("AutoRecordIncomingRow", "AutoRecordOutgoingRow")
    private val APP_ONLY = setOf("VoipAppPicker", "VoipAutoStartRow")
    private val ASK_ONLY = setOf("AfterCallAskStyleRow", "AfterCallUnansweredRow")

    /**
     * The rows [switch] folds away right now: its own rows while it is off, and "Record from answer" (shown
     * with either call switch) while both call switches are off.
     */
    fun foldedBy(switch: String, s: Switches): Set<String> = when (switch) {
        PHONE_SWITCH -> when {
            s.isPhoneOn -> emptySet()
            s.isAppOn -> PHONE_ONLY
            else -> PHONE_ONLY + RECORD_FROM_ANSWER
        }
        APP_SWITCH -> when {
            s.isAppOn -> emptySet()
            s.isPhoneOn -> APP_ONLY
            else -> APP_ONLY + RECORD_FROM_ANSWER
        }
        ASK_SWITCH -> if (s.isAsking) emptySet() else ASK_ONLY
        else -> emptySet()
    }

    /** What a search for [matches] puts on the screen: folded matches give way to their switches. */
    fun shownRows(matches: Set<String>, s: Switches): Set<String> {
        val switches = listOf(PHONE_SWITCH, APP_SWITCH, ASK_SWITCH)
        val folded = switches.flatMap { foldedBy(it, s) }.toSet()
        val unfolding = switches.filter { sw -> foldedBy(sw, s).any { it in matches } }
        return (matches - folded) + unfolding
    }
}
