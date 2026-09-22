/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * A caller learned after the recording started, and how it reaches the file name.
 *
 * The name is first asked for the instant the audio mode flips to a call, which can be before the
 * VoIP app has posted its call notification — the only place the name exists. The file is published
 * only when the call ends, so a name found a few seconds in goes into the name it is published
 * under; nothing is renamed.
 */
object VoipLateCaller {

    /**
     * When to ask again, from the start of the recording. Early enough to catch a notification
     * posted a moment after the audio, late enough that a slow app still gets a chance, and over
     * before a short call ends — the notification is usually gone by the time the end is detected.
     */
    val RETRY_DELAYS_MS: List<Long> = listOf(3_000L, 8_000L, 20_000L)

    /** The marker every VoIP file name carries, with or without an app after it. */
    private val VOIP_MARKER = Regex("""_voip(-[^_.]+)?(_[^.]+)?(\.[A-Za-z0-9]+)$""")

    /**
     * [fileName] with [caller] in its caller slot — `{stamp}_voip[-App]_{caller}.ext` — when the slot
     * is empty. A name that already has a caller, a blank caller, or a name that is not a VoIP
     * recording's is returned unchanged.
     */
    fun withCaller(fileName: String, caller: String): String {
        val who = caller.trim()
        if (who.isEmpty()) return fileName
        val match = VOIP_MARKER.find(fileName) ?: return fileName
        val (app, existing, extension) = match.destructured
        if (existing.isNotEmpty()) return fileName
        return fileName.substring(0, match.range.first) + "_voip$app" + "_$who" + extension
    }
}
