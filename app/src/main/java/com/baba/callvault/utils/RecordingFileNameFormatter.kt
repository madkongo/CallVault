/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.annotation.StringRes
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.system.permissions.PermissionChecks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingFileNameFormatter {
    const val TAG = "CV:RecordingFileNameFormatter"

    /**
     * `yyyyMMddHHmmss`, local time. Not offered as a placeholder of its own: it exists for
     * [HONOR_DIALER_TEMPLATE], whose phone app wants exactly this, and the library can read it back only there.
     */
    const val DATE_COMPACT_TAG = "{date_compact}"
    /**
     * Represents the supported placeholders that can be used in the file name template.
     * Binds the literal tag used in formatting to a localized description for the UI.
     * @param tag The literal placeholder string that will be replaced in the template (e.g., "{date}").
     * @param descriptionResId The string resource ID for the description of this placeholder
     */
    enum class FileNamePlaceholder(val tag: String, @param:StringRes val descriptionResId: Int) {
        DATE("{date}", R.string.placeholder_date_desc),
        DIRECTION("{direction}", R.string.placeholder_direction_desc),
        PHONE_NUMBER("{phone_number}", R.string.placeholder_phone_number_desc),
        CONTACT_NAME("{contact_name}", R.string.placeholder_contact_name_desc),
        CROSS_COUNTRY("{cross_country}", R.string.placeholder_cross_country_desc)
    }

    /**
     * Formats a filename based on the user defined string template and the recording metadata and audio codec.
     * Supported placeholders:
     * - {date}: The current date and time
     * - {direction}: The call direction (in/out)
     * - {phone_number}: The best available phone number
     * - {contact_name}: The contact name, if available
     * - {cross_country}: true/false indicating if the call is cross-country
     *
     * @param context The context needed to resolve contacts and read preferences.
     * @param metadata Defines the main properties (direction, phone number, cross country).
     * @param codec The selected ScrcpyAudioCodec used to determine the file extension.
     * @param customFormat An optional custom format string to use instead of the one from preferences. Useful for testing or one-off formatting without changing user settings.
     * @param startedAt When the recording started. A rename after the call must pass the original start,
     *   or the name would carry the time the call ended.
     * @return A filesystem-safe filename string.
     */
    fun formatFileName(
        context: Context,
        metadata: RecordingMetadata,
        codec: ScrcpyAudioCodec,
        customFormat: String? = null,
        startedAt: Date = Date()
    ): String {
        val template = customFormat ?: AppPreferences(context).getFileNameTemplate()
        // Honor's phone app matches the number as its call log holds it, not the E.164 form.
        val isHonorDialer = template == HONOR_DIALER_TEMPLATE

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", Locale.CANADA).format(startedAt)
        val compactDateStr = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(startedAt)

        val directionStr = when (metadata.direction) {
            RecordingDirection.INCOMING -> "in"
            RecordingDirection.OUTGOING -> "out"
        }

        val phoneStr = if (isHonorDialer) {
            dialledNumber(metadata.rawPhoneNumber) ?: dialledNumber(metadata.getBestNumber()).orEmpty()
        } else {
            metadata.getBestNumber() ?: ""
        }
        var contactStr = ""

        if (template.contains(FileNamePlaceholder.CONTACT_NAME.tag) && phoneStr.isNotEmpty()) {
            // Voicemail is not a real contact — PhoneLookup misses it, so fall back to its label.
            // For numbers with no saved contact at all, fall back to the number itself so the
            // filename still identifies the call — unless the template already carries the number
            // via {phone_number}, in which case duplicating it would just add noise.
            contactStr = getContactName(context, phoneStr)
                ?: VoicemailLabel.labelOrNull(context, phoneStr)
                ?: phoneStr.takeUnless { template.contains(FileNamePlaceholder.PHONE_NUMBER.tag) && !isHonorDialer }
                ?: ""
        }

        if (isHonorDialer) {
            // An unsaved caller's number stands in for the name, minus its '+': HnContacts compiles "name@" as a
            // regex, and a leading '+' would throw there and lose the match. The '+' stays on the number itself.
            val numberAsName = phoneStr.removePrefix("+")
            contactStr = if (contactStr == phoneStr) numberAsName else honorSafeName(contactStr).ifBlank { numberAsName }
        }

        val crossCountryStr = metadata.isCrossCountry.toString()

        val baseName = template
            .replace(DATE_COMPACT_TAG, compactDateStr)
            .replace(FileNamePlaceholder.DATE.tag, dateStr)
            .replace(FileNamePlaceholder.DIRECTION.tag, directionStr)
            .replace(FileNamePlaceholder.PHONE_NUMBER.tag, phoneStr)
            .replace(FileNamePlaceholder.CONTACT_NAME.tag, contactStr)
            .replace(FileNamePlaceholder.CROSS_COUNTRY.tag, crossCountryStr)

        // Log the FINAL name, not the base one. AppLogger's redaction recognises a recording filename
        // by its extension, so a base name would have walked past it with the contact still in it —
        // this was the one place in the app that logged a name with no extension.
        val fileName = "$baseName${codec.containerExtension}"
        AppLogger.v(TAG, "Formatted filename: '$fileName' with template '$template'")
        return fileName
    }

    /** Characters Honor's phone app cannot take in the name: it strips '_' and then uses the name as a regex. */
    private val HONOR_UNSAFE_NAME_CHARS = Regex("""[\\^$.|?*+()\[\]{}]""")

    /**
     * [name] made safe for the Honor preset: '_' becomes a space (HnContacts deletes '_' before the '@' and splits
     * on the rest), regex characters are dropped, spaces are collapsed. Blank when nothing usable is left.
     */
    internal fun honorSafeName(name: String): String =
        name.replace('_', ' ').replace(HONOR_UNSAFE_NAME_CHARS, "").replace(Regex("""\s+"""), " ").trim()

    /** Fewer digits than this is a placeholder ("-1", "Unknown 5"), not a number. Short codes like 087011 pass. */
    private const val MIN_DIALLED_DIGITS = 3

    /**
     * [number] as the call log shows it, for the Honor preset: digits only, keeping a leading '+'. Null for a
     * missing number or a placeholder, so a junk value is never written as the caller.
     */
    internal fun dialledNumber(number: String?): String? {
        val trimmed = number?.trim()?.trimStart('\u200E', '\u200F', '\u202A', '\u202B', '\u202C')?.trim() ?: return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < MIN_DIALLED_DIGITS) return null
        return if (trimmed.startsWith('+')) "+$digits" else digits
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        if (!PermissionChecks.hasContactsPermission(context)) return null

        val lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        return context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex != -1) {
                    cursor.getString(nameIndex)
                } else null
            } else null
        }
    }
}
