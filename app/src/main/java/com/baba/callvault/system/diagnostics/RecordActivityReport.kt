/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * Who is holding a microphone, according to Android rather than according to us.
 *
 * Parses the `RecordActivityMonitor` section of `dumpsys audio`, which logs a `rec start` / `update` /
 * `stop` per recording, each carrying a `riid`, the uid, the audio source and the package. An entry
 * with no matching `stop` *may* be a capture that was still open when the dump was taken.
 *
 * ## 🚨 What this section cannot tell you, and why
 *
 * **Some audio sources can never produce a `rec stop` at all**, so "no stop" is not evidence of
 * anything for them. The chain, from AOSP (`android16-release`):
 *
 * 1. `MediaRecorder.isSystemOnlyAudioSource()` treats `REMOTE_SUBMIX` as system-only — its case is
 *    deliberately commented out of the `return false` list.
 * 2. `RecordingActivityMonitor.onRecordingConfigurationChanged()` therefore logs the event and
 *    **returns early**, never calling `updateSnapshot()`. That riid's `mConfig` stays null for life.
 * 3. Client-side start/stop logging is gated on `configChanged`, which is `mConfig != null` — so it
 *    never logs.
 * 4. Server-side start/stop is separately suppressed for any client with a valid riid
 *    (`AudioInputDescriptor::updateClientRecordingConfiguration`).
 *
 * Only `rec update` survives. **A submix capture can therefore only ever appear as an unmatched
 * `update`. Always, on every healthy device.**
 *
 * This was verified the hard way: an earlier version of this file counted those entries as leaks and
 * reported **five "still open" captures on a phone with no symptom at all**, while every `MIC` and
 * `VOICE_CALL` capture in the same dump paired correctly and `dumpsys appops` showed the microphone
 * op closing cleanly. A whole investigation went the wrong way on it. Hence [MicOpReport], which reads
 * the app-op that actually drives the indicator.
 *
 * **Why this and not [com.baba.callvault.server.CaptureAudit].** That ledger lives inside whichever
 * process opened the capture, is only printed when something releases, and cannot see a capture held
 * by a process we no longer talk to — a leftover scrcpy server, a daemon from a previous app version,
 * or a Shizuku-hosted service. All of those present as uid 2000 / `com.android.shell`.
 */
object RecordActivityReport {

    /** Android's own uid for the shell user; every privileged capture of ours is attributed to it. */
    const val SHELL_UID = "2000"

    /**
     * `RecordingActivityMonitor`'s event log is `new EventLogger(50, …)`. Once full it evicts oldest
     * first, so a long-running capture's opening event can be pushed out entirely — and unpairable
     * entries below accumulate, which makes that *more* likely, not less.
     */
    const val RING_CAPACITY = 50

    /**
     * Sources whose events cannot be paired, per the chain in the class docs.
     *
     * `REMOTE_SUBMIX` is proven on-device. The rest are grouped with it by AOSP itself (they are the
     * sources `ServiceUtilities::isRecordOpRequired()` returns false for) and CallVault never opens any
     * of them, so excluding them can only ever affect another app's rows, never our own captures.
     */
    // HOTWORD (the always-on "Hey Google"/Bixby listener), RADIO_TUNER and ULTRASOUND are system-only sources
    // too. On a Samsung A56 (BabApps item 94) a HOTWORD `update` with no `stop` made the report claim that
    // "another app holds the mic" when it was the phone's own listener, which never logs a stop.
    val UNPAIRABLE_SOURCES = setOf(
        "REMOTE_SUBMIX", "ECHO_REFERENCE", "VOICE_DOWNLINK", "FM_TUNER", "HOTWORD", "RADIO_TUNER", "ULTRASOUND",
    )

    /**
     * True for sources that note `OP_RECORD_AUDIO` and can therefore light the privacy indicator.
     *
     * The rest note `OP_RECORD_AUDIO_OUTPUT` (op 106), which SystemUI's `AppOpsControllerImpl.OPS_MIC`
     * does not watch — so claiming they light the dot is simply false.
     */
    fun drivesMicIndicator(source: String): Boolean = source !in UNPAIRABLE_SOURCES

    private val EVENT = Regex(
        """rec (start|update|stop) riid:(\d+) uid:(\d+) session:(\d+) src:(\w+)\s*(\w[\w ]*?)?\s*pack:(\S+)"""
    )

    /** One capture with no matching `stop` when the dump was taken. */
    data class OpenCapture(
        val kind: String,
        val riid: String,
        val uid: String,
        val session: String,
        val source: String,
        val pack: String,
    ) {
        /** True for the uid CallVault's privileged captures run as — the ones a user sees as "Shell". */
        val isShell: Boolean get() = uid == SHELL_UID

        /** True when this source could not have been paired regardless of whether it leaked. */
        val isUnpairable: Boolean get() = source in UNPAIRABLE_SOURCES

        override fun toString(): String =
            "[$kind] riid=$riid uid=$uid session=$session src=$source pack=$pack" +
                if (isShell) "   <-- CallVault's privileged capture" else ""
    }

    /** Everything parsed out of the dump, so the caller can report coverage as well as findings. */
    data class Scan(
        val totalEvents: Int,
        val open: List<OpenCapture>,
        val unpairable: List<OpenCapture>,
    ) {
        /** The ring was full, so older events — possibly the one that mattered — have been evicted. */
        val ringSaturated: Boolean get() = totalEvents >= RING_CAPACITY
    }

    fun scan(dumpsysAudio: String): Scan {
        val pending = LinkedHashMap<String, OpenCapture>()
        var total = 0
        for (m in EVENT.findAll(dumpsysAudio)) {
            total++
            val (kind, riid, uid, session, src) = m.destructured
            val pack = m.groupValues[7]
            when (kind) {
                "stop" -> pending.remove(riid)
                else -> pending[riid] = OpenCapture(kind, riid, uid, session, src, pack)
            }
        }
        val (unpairable, open) = pending.values.partition { it.isUnpairable }
        return Scan(total, open, unpairable)
    }

    /**
     * Captures genuinely left open, oldest first — **excluding sources that can never be paired**.
     *
     * `update` still counts as an open the same way `start` does: the log is a ring, so a recording
     * that began before the window shows only its later `update`, and treating that as "not open"
     * would hide exactly the long-lived capture this exists to find.
     */
    fun openCaptures(dumpsysAudio: String): List<OpenCapture> = scan(dumpsysAudio).open

    /** Entries Android structurally cannot pair. Shown for completeness; never evidence of a leak. */
    fun unpairableEntries(dumpsysAudio: String): List<OpenCapture> = scan(dumpsysAudio).unpairable

    /** The section as it appears in the report: a verdict first, then the evidence. */
    fun render(dumpsysAudio: String?): String {
        if (dumpsysAudio.isNullOrBlank()) {
            return "Microphone activity: could not read `dumpsys audio` — section unavailable."
        }
        val scan = scan(dumpsysAudio)
        val shell = scan.open.filter { it.isShell }
        return buildString {
            appendLine("--- Microphone activity (from Android, not from CallVault) ---")
            appendLine("Read ${scan.totalEvents} recording event(s) from the ${RING_CAPACITY}-entry ring log.")
            when {
                scan.open.isEmpty() ->
                    appendLine("No capture was left open. Every pairable recording that started also stopped.")
                shell.isNotEmpty() ->
                    appendLine(
                        "STILL OPEN: ${scan.open.size} capture(s), ${shell.size} of them running as uid " +
                            "$SHELL_UID (shell) — that is CallVault's privileged capture."
                    )
                else ->
                    appendLine(
                        "STILL OPEN: ${scan.open.size} capture(s), none belonging to CallVault " +
                            "(uid $SHELL_UID). Another app is holding the microphone."
                    )
            }
            scan.open.forEach { appendLine("    $it") }

            if (scan.unpairable.isNotEmpty()) {
                appendLine()
                appendLine(
                    "${scan.unpairable.size} further entr(y/ies) below CANNOT BE PAIRED by Android and " +
                        "are NOT a leak: these sources never emit a `rec stop`, so they always look " +
                        "open. Listed only so this section is not mistaken for a parse failure."
                )
                scan.unpairable.forEach { appendLine("    $it") }
            }

            if (scan.ringSaturated) {
                appendLine()
                appendLine(
                    "⚠ The ring log is FULL (${scan.totalEvents}/$RING_CAPACITY), so older events have " +
                        "been evicted and a capture that started earlier may not appear here at all. " +
                        "Treat \"nothing open\" as \"nothing visible\", and read the microphone app-op " +
                        "section below instead — it is the one that reflects the green dot."
                )
            }
        }
    }
}
