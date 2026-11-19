/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.server.WifiJoinEvent
import com.baba.callvault.utils.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the recorder's Wi-Fi watch lines ([WifiJoinEvent]) into the always-on log (review N15), the same timeline as
 * the app's own "Wi-Fi approval (Shizuku)" lines, and remembers each access point Android now trusts.
 */
object WifiJoinEvents {

    private const val TAG = "CV:WifiApproveShizuku"
    const val PREFIX = "Wi-Fi approval (Shizuku): "
    private val NEWLINES = Regex("[\\r\\n]+")
    private const val MAX_KEY_CHARS = 64

    /** A line that reaches the app later than this says when the recorder saw it. */
    private const val LATE_MS = 10_000L

    /** Input from another process is capped: this many lines per hand-over, this many characters per line. */
    const val MAX_LINES = 64
    const val MAX_CHARS = 512

    fun record(context: Context, encoded: Array<String>?, nowMs: Long = System.currentTimeMillis()) {
        val events = encoded.orEmpty().take(MAX_LINES).mapNotNull { WifiJoinEvent.decode(it) }
        if (events.isEmpty()) return
        val prefs = AppPreferences(context)
        events.forEach { event ->
            val line = lineFor(event, nowMs)
            if (event.warn) AppLogger.w(TAG, line) else AppLogger.i(TAG, line)
            runCatching { TransportTimeline.event(context, line) }
                .onFailure { AppLogger.w(TAG, "Could not write the transport timeline: ${it.message}") }
            val key = event.trustedKey
            if (key.isNotBlank() && key.length <= MAX_KEY_CHARS && key.all { it.isLetterOrDigit() }) {
                prefs.addApprovedAccessPointKey(key, event.atMs)
            }
        }
    }

    fun lineFor(event: WifiJoinEvent, nowMs: Long): String {
        val late = nowMs - event.atMs > LATE_MS
        val seenAt = if (late) " (in the recorder at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.atMs))})" else ""
        val text = event.text.replace(NEWLINES, " ").take(MAX_CHARS)
        return PREFIX + text + seenAt
    }
}
