/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

/**
 * The decisions behind each group card's one-line summary on the new Settings screen, kept free of
 * Compose so they can be tested. The card turns them into text; the inputs are the same preferences the
 * group's rows read, so the summary cannot describe a different state from the switches under it.
 */

/** What the Recording card says about phone calls. */
enum class RecordingSummary { OFF, BOTH, INCOMING_ONLY, OUTGOING_ONLY, MANUAL_ONLY }

/** One switch as its row draws it: whether it is on, and whether the row is enabled at all. */
data class SwitchSummaryInput(val isOn: Boolean, val isAvailable: Boolean)

/** "[on] of [total] on". A total of zero means no switch in the group can be used here. */
data class CountSummary(val on: Int, val total: Int)

/** Separator between the parts of a summary line, e.g. "On · incoming and outgoing". */
internal const val SUMMARY_SEPARATOR = " · "

fun recordingSummary(isCarrierOn: Boolean, isIncomingOn: Boolean, isOutgoingOn: Boolean): RecordingSummary = when {
    !isCarrierOn -> RecordingSummary.OFF
    isIncomingOn && isOutgoingOn -> RecordingSummary.BOTH
    isIncomingOn -> RecordingSummary.INCOMING_ONLY
    isOutgoingOn -> RecordingSummary.OUTGOING_ONLY
    else -> RecordingSummary.MANUAL_ONLY
}

/**
 * Counts only the switches that can be used: a greyed row is drawn off whatever is stored, so counting
 * it would say "1 of 5 on" about a switch the user cannot see on.
 */
fun countSummary(switches: List<SwitchSummaryInput>): CountSummary {
    val usable = switches.filter { it.isAvailable }
    return CountSummary(on = usable.count { it.isOn }, total = usable.size)
}

/**
 * The "Stay paired" switch as the Connection count sees it: shown (so counted) in standalone mode, and in
 * Shizuku mode only when CallVault set it, matching [KeepPairedToggle].
 */
fun keepPairedSummaryInput(isOn: Boolean, usesEmbeddedAdb: Boolean, isSetByCallVault: Boolean) =
    SwitchSummaryInput(isOn, isAvailable = usesEmbeddedAdb || isSetByCallVault)

/** Joins the non-blank parts of a summary into one line. */
fun joinSummary(parts: List<String?>): String =
    parts.filterNot { it.isNullOrBlank() }.joinToString(SUMMARY_SEPARATOR)

/** Which kinds of call are recorded at all, for the "Calls to record" card. */
enum class CallsWhich { NONE, PHONE_ONLY, APP_ONLY, PHONE_AND_APP }

/** The "Calls to record" line: which calls, and for phone calls, which directions start by themselves. */
data class CallsToRecordSummary(val which: CallsWhich, val direction: RecordingSummary?)

/**
 * [isAppOn] must already account for the mode: an app-call switch greyed out here records nothing. The
 * direction is about phone calls only, since app calls have no incoming/outgoing switches.
 */
fun callsToRecordSummary(
    isPhoneOn: Boolean,
    isAppOn: Boolean,
    isIncomingOn: Boolean,
    isOutgoingOn: Boolean,
): CallsToRecordSummary {
    val which = when {
        isPhoneOn && isAppOn -> CallsWhich.PHONE_AND_APP
        isPhoneOn -> CallsWhich.PHONE_ONLY
        isAppOn -> CallsWhich.APP_ONLY
        else -> CallsWhich.NONE
    }
    val direction = if (isPhoneOn) recordingSummary(true, isIncomingOn, isOutgoingOn) else null
    return CallsToRecordSummary(which, direction)
}

/** How many skip rules are in force, for the "Calls to skip" card. Zero reads "Nothing skipped". */
fun activeRuleCount(rules: List<Boolean>): Int = rules.count { it }

/** A 24-hour clock time, "02:00", as the schedule dropdowns show it. */
fun clockTime(hour: Int, minute: Int): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
