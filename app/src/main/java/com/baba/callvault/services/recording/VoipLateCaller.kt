/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.voip.VoipAppPolicy

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

    /**
     * How long to wait before asking a second time about an app the user switched off. Throwing a
     * recording away mid-call costs the whole call, so one lookup is not enough to do it.
     */
    const val CONFIRM_DELAY_MS: Long = 1_000L

    /** What to do with an app found after the recording started. */
    enum class LateAppVerdict {
        /** Recording is allowed for it: name the file after it. */
        RECORD,

        /** Switched off, and a second lookup named the same app: stop and discard. */
        DISCARD,

        /** Switched off, but a second lookup disagreed: keep recording, unnamed, and ask again later. */
        UNSURE,
    }

    /**
     * Decides on a late-found app. [first] is the app the lookup found; [second] is a second lookup
     * made [CONFIRM_DELAY_MS] later, needed only when [first] is switched off. A wrong lookup must not
     * discard a call the user wanted, so a switched-off app needs both lookups to agree.
     */
    fun verdict(first: String, second: String?, excluded: Set<String>): LateAppVerdict = when {
        VoipAppPolicy.shouldRecord(first, excluded) -> LateAppVerdict.RECORD
        second == first -> LateAppVerdict.DISCARD
        else -> LateAppVerdict.UNSURE
    }

    /** The marker every VoIP file name carries, with or without an app after it. */
    private val VOIP_MARKER = Regex("""_voip(-[^_.]+)?(_[^.]+)?(\.[A-Za-z0-9]+)$""")

    /**
     * [fileName] with [app] on its marker — `{stamp}_voip-{app}[_{caller}].ext` — when it has none.
     *
     * For an app identified after the recording started: Telegram's call audio appears about 2.5 s
     * after the audio mode flips, past the start-time lookup. [app] must already be safe for a file
     * name. A name that already has an app, a blank app, or a name that is not a VoIP recording's is
     * returned unchanged.
     */
    fun withApp(fileName: String, app: String): String {
        val label = app.trim()
        if (label.isEmpty()) return fileName
        val match = VOIP_MARKER.find(fileName) ?: return fileName
        val (existing, caller, extension) = match.destructured
        if (existing.isNotEmpty()) return fileName
        return fileName.substring(0, match.range.first) + "_voip-$label" + caller + extension
    }

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
