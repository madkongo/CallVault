/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * A short summary of how the recorder had to build or start the current capture's records when the normal way did
 * not work (review N21b): the vendor constructor crash, the constructor-free rebuild and its field layout, the shell
 * Application and the retry, Honor's start repair — each with its failure reason.
 *
 * Those steps were logged only to the recorder's debug log, and a field reporter usually has debug logging off. The
 * summary rides on the existing `captureDiagnostics()` answer ([CaptureDiagnosticsSlot]) after [MARKER], so the AIDL
 * is unchanged and an older recorder simply sends none; the app writes it to the always-on call log
 * ([callEndLine]).
 *
 * A capture built the normal way records nothing: a normal build only notes itself when an earlier step of the same
 * capture already needed recovery ([noteNormalIfActive]), which costs one volatile read otherwise. No phone numbers
 * or names reach it — only record labels and framework exception messages.
 */
internal class CaptureRecoveryLog(
    private val shellAppState: () -> String = ShellApplication::state,
) {

    private val events = ArrayList<String>()
    private var dropped = 0

    @Volatile
    private var active = false

    /** Records one record's outcome, e.g. ("VoIP far-party sink", "ctor crashed(…) > rebuilt(A17 layout)"). */
    fun note(what: String, outcome: String) {
        val event = clip("${labelOf(what)}=${oneLine(outcome)}", MAX_EVENT)
        synchronized(this) {
            if (events.size < MAX_EVENTS) events += event else dropped++
            active = true
        }
    }

    /** A record built the normal way is only worth a word next to one that was not. */
    fun noteNormalIfActive(what: String) {
        if (active) note(what, "normal")
    }

    /** A new capture starts: nothing before it is its own. */
    fun reset() {
        synchronized(this) {
            events.clear()
            dropped = 0
            active = false
        }
    }

    /** The summary of everything since the last [reset] or [drain], then forgets it; "" when nothing was recorded. */
    fun drain(): String {
        val (taken, more) = synchronized(this) {
            if (!active) return ""
            val copy = events.toList() to dropped
            events.clear()
            dropped = 0
            active = false
            copy
        }
        val tail = buildList {
            if (more > 0) add("(+$more more)")
            add("shellApp=${runCatching(shellAppState).getOrDefault("?")}")
        }
        val head = taken.joinToString("; ")
        val room = MAX_SUMMARY - tail.sumOf { it.length + 2 }
        return (listOf(clip(head, room)) + tail).joinToString("; ")
    }

    companion object {
        /** The recorder process's one log: every capture it builds goes through [HostAudioRecord]. */
        val current = CaptureRecoveryLog()

        /** Separates the summary from the capture's own health in the `captureDiagnostics()` answer. */
        const val MARKER = "\ncapture recovery: "

        const val MAX_SUMMARY = 700
        private const val MAX_EVENTS = 8
        private const val MAX_EVENT = 300
        const val MAX_REASON = 100

        /** A failure as a short reason: its class and the start of its message. */
        fun reason(error: Throwable): String =
            clip(listOfNotNull(error.javaClass.simpleName, error.message?.takeIf { it.isNotBlank() }).joinToString(": "), MAX_REASON)

        /** [diagnostics] with [summary] after it; [diagnostics] unchanged when there is no summary. */
        fun append(diagnostics: String, summary: String): String =
            if (summary.isEmpty()) diagnostics else diagnostics + MARKER + summary

        /** The capture's own health and the recovery summary, as the app received them; either may be null. */
        fun split(diagnostics: String?): Pair<String?, String?> {
            if (diagnostics == null) return null to null
            val at = diagnostics.lastIndexOf(MARKER)
            if (at < 0) return diagnostics to null
            return diagnostics.substring(0, at) to diagnostics.substring(at + MARKER.length).takeIf { it.isNotBlank() }
        }

        /** The always-on line for one app call, or null when every record was built the normal way. */
        fun callEndLine(diagnostics: String?): String? =
            split(diagnostics).second?.let { "app call capture recovery: $it" }

        /** "VoIP far-party sink" → "sink", "VoIP near (resume)" → "mic (resume)"; other paths keep their words. */
        internal fun labelOf(what: String): String = what.removePrefix("VoIP ")
            .replace("far-party sink", "sink")
            .replace(Regex("^far\\b"), "sink")
            .replace(Regex("^near\\b"), "mic")

        private fun oneLine(text: String) = text.replace(Regex("[\\r\\n]+"), " ")

        private fun clip(text: String, max: Int) =
            if (text.length <= max) text else text.take((max - 1).coerceAtLeast(0)) + "…"
    }
}
