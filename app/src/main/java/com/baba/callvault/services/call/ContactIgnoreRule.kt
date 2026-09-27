/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import com.baba.callvault.data.AppPreferences.IgnoreContactsMode
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.PhoneNumberManager.Companion.normalisePhoneNumber

/**
 * Whether a call is skipped because of who it is with.
 *
 * **The two failure directions are not equal.** Wrongly ignoring means the call is silently never
 * recorded, and the user finds out when they go looking for it. Wrongly recording costs a file they can
 * delete. So every uncertain case here resolves to *record it* — a blank number, a contacts lookup that
 * throws, a permission that is not held.
 *
 * Its own file, and the lookup passed in rather than reached for, because this used to live inside
 * `CallSessionManager` where it could not be tested and where the interesting case — a provider that
 * objects — could not be exercised at all.
 */
object ContactIgnoreRule {

    private const val TAG = "CV:IgnoreRule"

    /**
     * @param normalisedNumber the other party, already normalised. May be blank: Teams, withheld
     *   numbers and short codes routinely arrive with nothing to match on.
     * @param isKnownContact resolves whether [normalisedNumber] belongs to a saved contact. Called at
     *   most once, only in [IgnoreContactsMode.ALL], and allowed to throw.
     */
    fun shouldIgnore(
        normalisedNumber: String,
        mode: IgnoreContactsMode,
        ignoredNumbers: Set<String>,
        isKnownContact: () -> Boolean,
        recordOnlyNumbers: Set<String> = emptySet(),
    ): Boolean {
        // "Record only these people": here skipping is the normal answer, so a call with nothing to
        // match on — withheld, Teams, a short code — is not recorded automatically. The user can still
        // press Record on the call's notification.
        if (mode == IgnoreContactsMode.ONLY_SELECTED) {
            if (normalisedNumber.isBlank()) return true
            return recordOnlyNumbers.none { sameNumber(it, normalisedNumber) }
        }
        // Nothing to match on. Previously this built a `PhoneLookup` URI out of an empty string and
        // asked the contacts provider about it, which can throw straight up through the call-state
        // handler — and in SELECTED mode a stray empty entry in the list would have matched, quietly
        // turning "ignore these contacts" into "ignore every unidentified call".
        if (normalisedNumber.isBlank()) return false
        return when (mode) {
            IgnoreContactsMode.NONE, IgnoreContactsMode.ONLY_SELECTED -> false
            IgnoreContactsMode.ALL -> runCatching { isKnownContact() }
                .onFailure { AppLogger.w(TAG, "Contact lookup failed; recording the call: ${it.message}") }
                .getOrDefault(false)
            // Deliberately does not consult contacts: a list of numbers is a list of numbers, and
            // reaching for the provider here would add both a permission dependency and a second way
            // to throw.
            IgnoreContactsMode.SELECTED ->
                ignoredNumbers.any { normalisePhoneNumber(it) == normalisedNumber }
        }
    }

    /**
     * Whether a number from the record-only list is the caller. The list holds numbers as the address book
     * has them ("050-000 0000") and the phone reports the caller internationally ("+972500000000"), so a
     * full-length number also matches on its last [TAIL_DIGITS] digits. Short codes must match exactly.
     */
    private fun sameNumber(listed: String, normalisedCaller: String): Boolean {
        val a = normalisePhoneNumber(listed).filter { it.isDigit() }
        val b = normalisedCaller.filter { it.isDigit() }
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.length >= TAIL_DIGITS && b.length >= TAIL_DIGITS && a.takeLast(TAIL_DIGITS) == b.takeLast(TAIL_DIGITS)
    }

    /** Digits a subscriber number keeps across its national and international forms (Israel, most of Europe: 9). */
    private const val TAIL_DIGITS = 9
}
