/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.baba.callvault.R
import com.baba.callvault.system.permissions.PermissionChecks

/**
 * Detects the carrier voicemail number so it can be labeled like a contact.
 *
 * Voicemail is usually NOT a real contact: stock dialers display "Voicemail" by comparing against
 * [TelephonyManager.getVoiceMailNumber], not via a ContactsContract lookup. Contact resolution
 * therefore comes up empty for calls to voicemail (e.g. the FR short code "123") unless callers
 * fall back to this helper.
 */
object VoicemailLabel {
    /**
     * Device voicemail number memoized after the first successful fetch:
     * [TelephonyManager.getVoiceMailNumber] is a cross-process binder call and the value is
     * effectively constant for the process lifetime (a SIM swap resets it only after restart).
     * Failed fetches (permission denied, telephony not ready) are NOT memoized, so the lookup
     * retries once conditions improve.
     */
    @Volatile
    private var cachedVoicemailNumber: String? = null

    /**
     * True when [number] is the device's voicemail number. Compares against
     * [TelephonyManager.getVoiceMailNumber] and falls back to [PhoneNumberUtils.isVoiceMailNumber].
     * Both telephony calls require READ_PHONE_STATE, so without that permission this is always
     * false. Never throws; returns false on a blank number or any failure.
     */
    fun isVoicemailNumber(context: Context, number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        if (!PermissionChecks.hasPhoneStatePermission(context)) return false
        val trimmed = number.trim()

        val deviceVoicemail = cachedVoicemailNumber ?: runCatching {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.voiceMailNumber
        }.getOrNull()?.also { fetched ->
            if (fetched.isNotBlank()) cachedVoicemailNumber = fetched
        }
        if (!deviceVoicemail.isNullOrBlank() && PhoneNumberUtils.compare(context, trimmed, deviceVoicemail)) {
            return true
        }

        return runCatching { PhoneNumberUtils.isVoiceMailNumber(trimmed) }.getOrDefault(false)
    }

    /**
     * The localized "Voicemail" display label when [number] is the voicemail number, else null.
     * Meant as a fallback after a contact lookup returns no match.
     */
    fun labelOrNull(context: Context, number: String?): String? =
        if (isVoicemailNumber(context, number)) {
            context.getString(R.string.voicemail_contact_label)
        } else {
            null
        }
}
