/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The always-on log of the "Call redirecting app" test (see [CallRedirectDiagService]). It is written whether or not
 * Debug logging is on, because the question it answers (does the phone start us in time, and how fast do we answer)
 * is only worth asking over days of ordinary use, and nobody keeps Debug logging on for days.
 *
 * Small on purpose: one line per outgoing call, trimmed to its newer half at [MAX_BYTES]. Never holds a phone number.
 * Every method swallows its own failures.
 */
object CallRedirectDiagLog {

    private const val FILE_NAME = "call_redirect_diag.txt"
    private const val MAX_BYTES = 64 * 1024

    private val lock = Any()

    fun record(context: Context, line: String) = synchronized(lock) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$stamp $line\n")
            if (file.length() > MAX_BYTES) {
                val text = file.readText()
                file.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        }
        Unit
    }

    fun readForReport(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()
}
