/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.VoicemailLabel

/**
 * The contact a single recording is with, resolved from the recording's own name (R45).
 *
 * The transcription prompt used to get this by listing the WHOLE library (call log, contacts and a duration
 * probe per row) and picking one row out of it: 0.3-2 s of CPU per recording at 5,000 rows, for one name. The
 * name comes from the file name and at most one contact lookup, so that is all this does. The rule is the one
 * [RecordingsRepository.listRecordings] applies: the name in the file, replaced by a live contact (or the
 * voicemail label) when the file carries a number to look up.
 */
internal object ContactNameResolver {

    fun nameFor(context: Context, displayName: String): String? {
        val parsed = RecordingsRepository.parseName(displayName)
        val lookupKey = parsed.number?.takeUnless { it.isBlank() }
            ?: parsed.contactName?.takeIf { RecordingsRepository.looksLikePhoneNumber(it) }
            ?: return parsed.contactName
        val resolved = (if (PermissionChecks.hasContactsPermission(context)) lookup(context, lookupKey) else null)
            ?: VoicemailLabel.labelOrNull(context, lookupKey)
        return resolved?.takeIf { it.isNotEmpty() } ?: parsed.contactName
    }

    private fun lookup(context: Context, phoneNumber: String): String? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
            if (index == -1) null else cursor.getString(index)
        }
    }.getOrNull()
}
