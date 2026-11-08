/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.content.Context
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.transcription.AudioDecoder

/**
 * How a held recording is named to the user, shared by the notification and the pop-up so the two always
 * describe the same call the same way.
 *
 * Who the call was with comes from the file name: the contact, else the number, else the app the call was
 * made in. Parsed from the name rather than carried from the call, so carrier and app calls read alike and a
 * renamed file (anonymous number filled in from the call log) is named correctly.
 */
internal object AfterCallText {

    private const val SEPARATOR = " · "

    /** "Call with Dana recorded · 4:07", "WhatsApp call recorded", or "Call recorded". */
    fun notificationTitle(context: Context, displayName: String, durationMs: Long): String {
        val (party, app) = whoOf(displayName)
        val head = when {
            party != null -> context.getString(R.string.after_call_title_party, party)
            app != null -> context.getString(R.string.after_call_title_app, app)
            else -> context.getString(R.string.after_call_title_plain)
        }
        val duration = AfterCallPolicy.formatDuration(durationMs) ?: return head
        return head + SEPARATOR + duration
    }

    /**
     * The pop-up's line under "Keep this recording?": "Dana · 4:07". No "just now": the card can be looked at
     * long after the call, behind other calls' questions.
     */
    fun popupSubtitle(context: Context, displayName: String, durationMs: Long): String {
        val (party, app) = whoOf(displayName)
        val who = party ?: app ?: context.getString(R.string.after_call_popup_call)
        return listOfNotNull(who, AfterCallPolicy.formatDuration(durationMs)).joinToString(SEPARATOR)
    }

    /** The recording's length from its container; 0 (unknown, omitted) when it cannot be read. Blocking. */
    fun durationOf(context: Context, held: AfterCallDecisions.Held): Long =
        runCatching { AudioDecoder.durationMs(context, held.uri) }.getOrDefault(0L)

    private fun whoOf(displayName: String): Pair<String?, String?> {
        val parsed = runCatching { RecordingsRepository.parseName(displayName) }.getOrNull()
        val party = parsed?.contactName?.takeIf { it.isNotBlank() } ?: parsed?.number?.takeIf { it.isNotBlank() }
        val app = parsed?.voipApp?.takeIf { it.isNotBlank() }
        return party?.let(::isolated) to app?.let(::isolated)
    }

    /**
     * Keeps a name's own direction from deciding the line's: a Hebrew name first made the pop-up read
     * "4:07 · דנה" in an English app (seen on the OP9, 2026-11-07). Same marks as the Recordings list uses.
     */
    private fun isolated(text: String): String = "\u2068$text\u2069"
}
