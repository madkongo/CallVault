/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

/**
 * What the always-on [SetupJournal] may keep, and for how long.
 *
 * The debug log is off by default, so the first minutes after an install — the ones that fail on
 * phones we do not own — are recorded nowhere. Issue #23 is that exactly: a reporter stuck from the
 * first launch, asked for logs, and by then the moment was gone. This journal fills that gap, and
 * because nobody opted into it, the rules are deliberately narrow.
 *
 * **Setup and transport only.** Not calls, not audio, not transcripts, not numbers — a tag joins the
 * list below by being named, never by default.
 *
 * **Bounded, and it stops rather than rotates.** Keeping the FIRST run is the whole point; a rotation
 * would throw away the part that explains a phone that never worked in favour of the hundredth
 * healthy launch.
 *
 * **Finished once setup works.** The moment a recorder is connected, the journal has done its job and
 * writes nothing more, so a healthy phone never pays for it beyond the first success.
 */
object SetupJournalPolicy {

    /** Roughly two hundred lines of setup — enough for several failed attempts, small enough to send. */
    const val MAX_BYTES = 64L * 1024

    /**
     * The tags the journal keeps.
     *
     * Each one is part of getting a recorder running: finding the ADB service, talking to it, the
     * daemon launch, the privileged grants, the modes, and the paths that repair themselves after a
     * reboot or an update. `CV:DaemonKeepAlive` is deliberately absent — it re-warms on a timer, and
     * at a fixed cap its loop would crowd out the one run this file exists to hold.
     */
    private val TAGS = setOf(
        "CV:AdbMdns",
        "CV:AdbShell",
        "CV:AdbConnectionMgr",
        "CV:AdbConnectionService",
        "CV:AdbPairing",
        "CV:ConnectPort",
        "CV:SetupReport",
        "CV:RecorderLauncher",
        "CV:RecorderBackend",
        "CV:RecorderConn",
        "CV:RecorderReadiness",
        "CV:ShizukuBackend",
        "CV:ServerExtractor",
        "CV:Grants",
        "CV:OfflineRecording",
        "CV:UsbDefault",
        "CV:BootReceiver",
        "CV:UpdateReplacedRecv",
        "CV:CallVaultApplication",
    )

    /**
     * How long an identical line is kept out after it was last written.
     *
     * From the BabApps item 92 reporter's log: the recorder relaunch failed once a minute for 143 minutes
     * (12:49 to 15:12) and was still failing. Written line by line, those repeats would fill the 64 KB cap in a
     * few hours and the pairing attempt after them would not fit. 30 minutes turns 143 repeats into about five.
     */
    const val REPEAT_WINDOW_MS = 30 * 60_000L

    /** Tags driven by what the user does, never thinned: a second pairing attempt must show up as its own lines. */
    private val NEVER_THINNED = setOf("CV:AdbPairing", "CV:AdbMdns", "CV:SetupReport", "CV:ConnectPort")

    /** Whether this line belongs in the journal. Cheap: it is asked of every log line in the app. */
    fun shouldRecord(tag: String, isClosed: Boolean, bytesWritten: Long): Boolean =
        !isClosed && bytesWritten < MAX_BYTES && tag in TAGS

    fun isNeverThinned(tag: String): Boolean = tag in NEVER_THINNED
}

/**
 * Keeps a line that repeats itself from filling the journal: the same tag and message inside
 * [SetupJournalPolicy.REPEAT_WINDOW_MS] of its last written copy is left out and counted, and the count is
 * reported with the next copy that is written. Tags in [SetupJournalPolicy.isNeverThinned] always pass.
 *
 * Not thread-safe on its own; [SetupJournal] calls it under its lock.
 */
class RepeatGate(private val windowMs: Long = SetupJournalPolicy.REPEAT_WINDOW_MS, private val maxKeys: Int = 256) {

    private class Seen(var lastWrittenMs: Long, var leftOut: Int)

    private val seen = object : LinkedHashMap<String, Seen>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?) = size > maxKeys
    }

    /** -1: leave this line out. 0 or more: write it, after noting that many identical lines were left out. */
    fun admit(tag: String, message: String, nowMs: Long): Int {
        if (SetupJournalPolicy.isNeverThinned(tag)) return 0
        val key = "$tag $message"
        val entry = seen[key]
        if (entry == null) {
            seen[key] = Seen(nowMs, 0)
            return 0
        }
        if (nowMs - entry.lastWrittenMs < windowMs) {
            entry.leftOut++
            return -1
        }
        val skipped = entry.leftOut
        entry.leftOut = 0
        entry.lastWrittenMs = nowMs
        return skipped
    }
}
