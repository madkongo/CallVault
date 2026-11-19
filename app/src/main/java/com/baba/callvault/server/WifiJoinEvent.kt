/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * One line for the app's always-on log about Shizuku mode's Wi-Fi approval (review N15), made in the recorder and
 * written by the app: the recorder cannot write the app's files, and on a release build the always-on log is the only
 * place such a line survives. [trustedKey] is the access point's salted key ([BssidPrivacy]) when Android now trusts
 * it, for the app to remember; empty otherwise. Text carries only masked addresses.
 */
data class WifiJoinEvent(val atMs: Long, val warn: Boolean, val text: String, val trustedKey: String = "") {

    fun encode(): String = listOf(atMs.toString(), if (warn) "1" else "0", trustedKey, text).joinToString(SEP)

    companion object {
        private const val SEP = "|"
        private const val FIELDS = 4

        /** Null for anything that is not an event of this shape. The text is last, so it may hold the separator. */
        fun decode(encoded: String?): WifiJoinEvent? {
            val parts = encoded?.split(SEP, limit = FIELDS) ?: return null
            if (parts.size != FIELDS) return null
            val at = parts[0].toLongOrNull() ?: return null
            return WifiJoinEvent(at, parts[1] == "1", parts[3], parts[2])
        }
    }
}

/** How a [KeptSwitchResult] reads in the always-on log, and whether it is a warning. */
object KeptSwitchReport {

    fun describe(answer: KeptSwitchResult): Pair<Boolean, String> {
        val ap = answer.maskedBssid.ifEmpty { "?" }
        val ms = answer.restoreMs
        return when (answer.outcome) {
            KeptSwitchOutcome.APPROVED_RESTORED_OFF -> false to
                "approved access point $ap (always allow); Wireless debugging restored off after $ms ms (undoing the approval's own switch-on)"
            KeptSwitchOutcome.APPROVED_RESTORE_FAILED -> true to
                "approved access point $ap (always allow); FAILED to switch Wireless debugging back off after $ms ms, it is left on"
            KeptSwitchOutcome.APPROVED_RESTORE_NOT_CONFIRMED -> true to
                "approval for $ap sent, but Wireless debugging was not seen on within $ms ms; nothing written, will try again on the next join"
            KeptSwitchOutcome.APPROVAL_FAILED -> true to "failed: the recorder could not approve $ap"
            KeptSwitchOutcome.ALREADY_APPROVED -> false to
                "skipped: $ap approved within the last 30 days (Android's own trusted list could not be read)"
            KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID -> false to
                "skipped: $ap is already on Android's trusted list for Wireless debugging"
            KeptSwitchOutcome.NOT_TCP_MODE -> false to
                "skipped: Shizuku not in tcp mode (adbd has no tcp port), so switching Wireless debugging back off could stop it; nothing approved or written"
            KeptSwitchOutcome.WIRELESS_DEBUGGING_TURNED_ON_MEANWHILE -> false to
                "skipped: Wireless debugging was switched on just before approving $ap; left on, nothing written"
            KeptSwitchOutcome.WIRELESS_DEBUGGING_ON -> false to
                "skipped: Wireless debugging is on at $ap (Android ignores an approval then)"
            KeptSwitchOutcome.WIRELESS_DEBUGGING_UNREADABLE -> true to
                "skipped: the recorder cannot read Wireless debugging, so it could not be put back"
            KeptSwitchOutcome.NO_WIFI_IDENTITY -> false to "skipped: the recorder sees no Wi-Fi network"
        }
    }
}
