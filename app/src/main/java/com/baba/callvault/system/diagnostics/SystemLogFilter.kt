/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * Deciding which logcat lines may go into a shareable report.
 *
 * **This is a privacy filter first and a noise filter second.** A raw logcat dump carries other
 * applications' logs and telephony lines containing phone numbers, and these reports get posted
 * publicly on GitHub issues. Everything not on the whitelist is dropped, so an unfamiliar tag is
 * excluded by default rather than included by default — the safe direction when the cost of being
 * wrong is a stranger's contacts in a public thread.
 *
 * Kept pure so the whitelist can be tested exhaustively without a device.
 */
object SystemLogFilter {

    /**
     * Tags allowed through, beyond our own `CV:` prefix.
     *
     * Each earns its place by having answered, or being able to answer, a real question:
     *  - the audio stack is where "recorded but silent" is decided, and where One UI logged the
     *    `rec update … silenced` line that explained the Samsung VoIP bug
     *  - `avc`/`SELinux` explain a denial that looks like a logic bug from our side
     *  - `ActivityManager`, `lowmemorykiller`, `libc`, `DEBUG` say how and why we or the daemon died
     *  - `adbd` transport churn has cost recordings before, more than once
     */
    private val ALLOWED_TAGS = setOf(
        "AudioFlinger", "AudioPolicyService", "AudioPolicyManager", "AudioRecord", "AudioTrack",
        // Distinguishes the TWO ways the microphone app-op can be left started, which look identical
        // from outside and need opposite fixes. This tag carries AudioPolicyService::releaseInput's
        //     ALOGW("%s releasing active client portId %d")
        // — an input released while still active, i.e. the path that skips finishRecording() because
        // only stopInput() calls it. If that line is present when the green dot is stuck, it is the
        // release-without-stop path (what the handoff teardown fix addresses). If the dot is stuck and
        // this line is ABSENT, it is instead the `if (!client->silenced)` guard inside stopInput
        // swallowing the finish — a different bug that our fix does not touch.
        "AudioPolicyInterfaceImpl",
        "avc", "SELinux", "auditd",
        "ActivityManager", "lowmemorykiller", "libc", "DEBUG", "tombstoned",
        "adbd", "app_process",
    )

    /**
     * Tags whose lines are kept only when they are **about us**.
     *
     * Found by reading a real report rather than by reasoning: `ActivityManager` is on the whitelist
     * because it says how and why our process died, but it narrates every process on the device, so
     * the file filled with lines like `Process com.paypal.android.p2pmobile has died` and
     * `pkg=com.alibaba.aliexpresshd`. A whitelisted *tag* is not a whitelisted *line* — that is an
     * inventory of the user's installed apps, going into a public issue.
     *
     * The audio tags are deliberately NOT in here: the line that explained the Samsung VoIP bug named
     * `com.android.shell` and no CallVault identifier at all, so a mentions-us rule would have dropped
     * the single most valuable line we have ever collected.
     */
    private val ABOUT_US_ONLY = setOf(
        "ActivityManager", "lowmemorykiller", "libc", "DEBUG", "tombstoned",
        "avc", "SELinux", "auditd",
    )

    /**
     * How a line says it concerns CallVault: the app package as a whole word (so `com.baba.callvaultpro` is
     * somebody else), or the daemon's process and shell uid.
     */
    private val OURS = listOf(
        Regex("""(?<![A-Za-z0-9_.])com\.baba\.callvault(?![A-Za-z0-9_])"""),
        Regex.fromLiteral("app_process"), Regex.fromLiteral("com.android.shell"), Regex.fromLiteral("uid:2000"),
    )

    /** Our own lines, app and daemon alike. */
    private const val OWN_TAG_PREFIX = "CV:"

    /**
     * Whether [line] — one line of `logcat -v threadtime` — belongs in the report.
     *
     * A line whose tag cannot be found is dropped rather than passed through. Malformed input is the
     * case most likely to smuggle something unexpected into a public issue.
     */
    fun keep(line: String): Boolean {
        val tag = tagOf(line) ?: return false
        if (tag.startsWith(OWN_TAG_PREFIX)) return true
        if (tag !in ALLOWED_TAGS) return false
        if (tag in ABOUT_US_ONLY) return OURS.any { it.containsMatchIn(line) }
        return true
    }

    /**
     * The tag from a `threadtime` line, or null when the line does not have that shape.
     *
     * Format: `MM-DD HH:MM:SS.mmm  PID  TID L TAG: message`.
     *
     * The tag ends at the first colon **followed by a space**, not at the first colon: our own tags
     * are `CV:RecorderConn` and the like, so stopping at the first colon truncates every one of them
     * to `CV` — which then fails to match the whitelist it was supposed to satisfy.
     */
    fun tagOf(line: String): String? {
        val match = THREADTIME.find(line) ?: return null
        return match.groupValues[1].trim().ifEmpty { null }
    }

    /**
     * Trims [lines] to the last [maxLines], and then to [maxBytes], always keeping the newest.
     *
     * Truncating from the oldest end is the whole point: a report is read to find out what happened
     * just before the failure, so the end of the log is the part worth its bytes.
     */
    fun capToNewest(lines: List<String>, maxLines: Int, maxBytes: Int): List<String> {
        var kept = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        var bytes = kept.sumOf { it.toByteArray().size + 1 }
        if (bytes <= maxBytes) return kept
        // Drop from the front until it fits. Counting down rather than re-summing each time keeps
        // this linear on a report that can run to tens of thousands of lines.
        var from = 0
        while (from < kept.size && bytes > maxBytes) {
            bytes -= kept[from].toByteArray().size + 1
            from++
        }
        kept = kept.drop(from)
        return kept
    }

    // ---------------------------------------------------------------------------------------------
    // Lines about CallVault from the rest of the system
    //
    // The vivo Android 17 report of 2026-11-20 crashed three times with "Bad notification for
    // startForeground" and carried not one system_server line about it, so why Android refused the
    // notice had to be read out of code. [select] widens the report by IDENTITY, never by keyword:
    // a line is let in because it names our package or our uid exactly, or because one of our own
    // processes wrote it. Another app's line about another app stays out, whatever its tag.
    // ---------------------------------------------------------------------------------------------

    /**
     * Who "us" is, for [select]: the package (which also covers `:recorder` / `:drive` process names
     * and our component names), the app's uid, and pids Android's exit record says were ours.
     */
    data class Ours(
        val packageName: String,
        val uid: Int,
        val pidWindows: List<PidWindow> = emptyList(),
    )

    /**
     * [pid] counts as ours between [fromMs] and [toMs], on the clock of [logClockMillis].
     *
     * Windowed because pids are reused: a number that was our process an hour ago may be someone
     * else's chat app now, and its lines must not ride in on our old pid.
     */
    data class PidWindow(val pid: Int, val fromMs: Long, val toMs: Long)

    /**
     * System tags whose lines are let in when they name us. Lifecycle, foreground services and their
     * notifications, jobs, app-ops, packages, power allow-lists and the runtime's own crash lines.
     */
    private val SYSTEM_TAGS = setOf(
        "ActivityManager", "ActivityTaskManager", "ActiveServices", "ServiceRecord", "AppErrors",
        "ProcessList", "Process", "Zygote", "AndroidRuntime", "libprocessgroup", "lmkd", "lowmemorykiller",
        "NotificationService", "NotificationRecord", "ForegroundServiceTypePolicy", "ForegroundServiceTypeLoggerModule",
        "JobScheduler", "JobScheduler.Quota", "JobServiceContext", "JobStore", "AlarmManager",
        "AppOps", "AppOpsService", "PackageManager", "PackageInstaller", "PermissionManager",
        "DeviceIdleController", "DeviceIdle", "PowerManagerService", "AppStandbyController",
        "UsageStatsService", "BatteryOptimization", "PowerExemptionManager", "CachedAppOptimizer",
    )

    /** Android's event log tags (`-b events`): am_crash, am_anr, am_kill, am_proc_died, notification_*. */
    private val EVENT_TAG_PREFIXES = listOf("am_", "notification_")

    /**
     * Phone makers' own process killers and freezers, by tag: vivo, OPPO/OnePlus/realme (Hans, athena,
     * oplus), Xiaomi (PowerKeeper, MIUI), Honor/Huawei (iAware, PowerGenie), Samsung (Freecess).
     * Only ever let in together with a line that names us.
     */
    private val OEM_KILLER_TAG = Regex(
        "(?i)vivo|bbk|hans|athena|oplus|oppo|coloros|oneplus|powerkeeper|miui|xiaomi|joyose|greeze|" +
            "honor|huawei|iaware|powergenie|freecess|smartpower|kill|freez",
    )

    /**
     * Phrases that say Android refused a foreground notice. The system_server lines carrying them name
     * no package, so they are let in only next to a death of ours (see [CORRELATION_MS]).
     */
    private val NOTICE_FAILURE = Regex(
        "Error showing notification for service|invalid (channel for )?service notification|Bad notification",
    )

    /** How close in time a [NOTICE_FAILURE] line must be to a line about us to be counted as ours. */
    private const val CORRELATION_MS = 5_000L

    /** How long before our first own line, and after our last, a pid still counts as ours. */
    private const val PID_LEAD_MS = 10_000L
    private const val PID_TRAIL_MS = 30_000L

    /** At most this many lines of one refused-notice stack trace. */
    private const val MAX_GROUP_LINES = 25

    private class Parsed(val tag: String, val pid: Int, val tid: Int, val atMs: Long)

    /**
     * The lines of [lines] that belong in the report, in their original order: everything [keep]
     * allows, plus what the rest of the system said about us — see [Ours].
     */
    fun select(lines: List<String>, ours: Ours): List<String> {
        val parsed = lines.map(::parse)
        val windows = ours.pidWindows + ownPidWindows(parsed)
        val namesUs = namesUsMatcher(ours)
        val kept = BooleanArray(lines.size)
        val aboutUsTimes = mutableListOf<Long>()

        lines.forEachIndexed { i, line ->
            val p = parsed[i] ?: return@forEachIndexed
            val ownPid = windows.any { it.pid == p.pid && p.atMs in it.fromMs..it.toMs }
            val systemAboutUs = isSystemTag(p.tag) && namesUs(line)
            if (ownPid || systemAboutUs) aboutUsTimes += p.atMs
            kept[i] = keep(line) || ownPid || systemAboutUs
        }

        noticeFailureGroups(lines, parsed)
            .filter { group -> aboutUsTimes.any { kotlin.math.abs(it - parsed[group.first()]!!.atMs) <= CORRELATION_MS } }
            .forEach { group -> group.forEach { kept[it] = true } }

        return lines.filterIndexed { i, _ -> kept[i] }
    }

    /**
     * Two dumps of the same log as one list, oldest first, without repeating a line both carried.
     * Stable for lines with the same timestamp, so a stack trace keeps its order.
     */
    fun mergeByTime(a: List<String>, b: List<String>): List<String> =
        LinkedHashSet(a + b).sortedBy { it.take(STAMP_LENGTH) }

    /**
     * A `threadtime` timestamp (`MM-DD HH:MM:SS.mmm`) as milliseconds on a clock that only has to
     * order and subtract within one log: months count as 31 days, and there is no year.
     */
    fun logClockMillis(stamp: String): Long? {
        val m = STAMP.find(stamp) ?: return null
        val (mo, d, h, mi, s, ms) = m.destructured
        val days = mo.toLong() * 31 + d.toLong()
        return ((days * 24 + h.toLong()) * 60 + mi.toLong()) * 60_000 + s.toLong() * 1_000 + ms.toLong()
    }

    /** The windows in which each pid of [lines] counts as ours: [Ours.pidWindows] and every pid that wrote a CV line. */
    fun ownWindows(lines: List<String>, ours: Ours): List<PidWindow> = ours.pidWindows + ownPidWindows(lines.map(::parse))

    /** A line's pid and its time on the [logClockMillis] clock, or null when it is not a `threadtime` line. */
    fun pidAndTime(line: String): Pair<Int, Long>? = parse(line)?.let { it.pid to it.atMs }

    /** Whether [line] comes from one of the system tags [select] accepts a line about us from. */
    fun isSystemTagLine(line: String): Boolean = tagOf(line)?.let(::isSystemTag) == true

    private fun parse(line: String): Parsed? {
        val m = HEADER.find(line) ?: return null
        val at = logClockMillis(line) ?: return null
        return Parsed(m.groupValues[3].trim(), m.groupValues[1].toInt(), m.groupValues[2].toInt(), at)
    }

    /** Every pid that wrote one of our own `CV:` lines, for the stretch it was writing them. */
    private fun ownPidWindows(parsed: List<Parsed?>): List<PidWindow> =
        parsed.filterNotNull()
            .filter { it.tag.startsWith(OWN_TAG_PREFIX) }
            .groupBy { it.pid }
            .map { (pid, own) ->
                PidWindow(pid, own.minOf { it.atMs } - PID_LEAD_MS, own.maxOf { it.atMs } + PID_TRAIL_MS)
            }

    private fun isSystemTag(tag: String): Boolean =
        tag in SYSTEM_TAGS || EVENT_TAG_PREFIXES.any { tag.startsWith(it) } || OEM_KILLER_TAG.containsMatchIn(tag)

    /**
     * Whether a line names us: the package as a whole word (so `com.baba.callvaultpro` is not us, but
     * `com.baba.callvault:recorder` and `com.baba.callvault/u0a24` are), or the uid in either of the
     * forms Android prints it (`10024` after "uid", or `u0a24`), never as part of a longer number.
     */
    fun namesUsMatcher(ours: Ours): (String) -> Boolean {
        val pkg = Regex("(?<![A-Za-z0-9_.])" + Regex.escape(ours.packageName) + "(?![A-Za-z0-9_])")
        val user = ours.uid / PER_USER_RANGE
        val appId = ours.uid % PER_USER_RANGE
        val uidPatterns = if (appId < FIRST_APPLICATION_UID) emptyList() else listOf(
            Regex("(?i)\\buid[ =:#]*" + ours.uid + "(?![0-9])"),
            Regex("(?<![A-Za-z0-9])u" + user + "a" + (appId - FIRST_APPLICATION_UID) + "(?![0-9])"),
        )
        return { line -> pkg.containsMatchIn(line) || uidPatterns.any { it.containsMatchIn(line) } }
    }

    /**
     * Each refused-notice line with the stack trace system_server printed under it: the following
     * lines from the same pid, thread and tag, up to [MAX_GROUP_LINES].
     */
    private fun noticeFailureGroups(lines: List<String>, parsed: List<Parsed?>): List<List<Int>> {
        val groups = mutableListOf<List<Int>>()
        var i = 0
        while (i < lines.size) {
            val head = parsed[i]
            if (head == null || head.tag !in SYSTEM_TAGS || !NOTICE_FAILURE.containsMatchIn(lines[i])) {
                i++
                continue
            }
            val group = mutableListOf(i)
            var j = i + 1
            while (j < lines.size && group.size < MAX_GROUP_LINES) {
                val next = parsed[j] ?: break
                if (next.pid != head.pid || next.tid != head.tid || next.tag != head.tag) break
                group += j
                j++
            }
            groups += group
            i = j
        }
        return groups
    }

    // ---------------------------------------------------------------------------------------------
    // Cutting the log down on the phone
    //
    // The recorder used to send the whole main+system log over the binder. With the 16 MB ring the
    // host sets, that is tens of megabytes against a 1 MB binder limit; the call failed, the app fell
    // back to its own ADB shell, and Shizuku mode refuses that — so the vivo report of 2026-11-20 had
    // no main or system log at all. [prefilter] runs IN THE RECORDER over the streamed log and keeps a
    // superset of what [select] can keep, newest first, within a budget well under the limit, with a
    // one-line summary on top saying how much it read and how much it had to leave out.
    // ---------------------------------------------------------------------------------------------

    /** What [prefilter] sends: the summary of the whole log, and the lines it kept, oldest first. */
    data class Prefiltered(
        val read: Int,
        val oldest: String?,
        val newest: String?,
        val truncated: Int,
        val lines: List<String>,
    )

    /** Characters the recorder may send: ~600 KB as the binder's UTF-16, well under its 1 MB limit. */
    const val PREFILTER_MAX_CHARS = 300_000

    private const val PREFILTER_MARK = "#cv-logcat"

    /** `MM-DD HH:MM:SS`, the part of a timestamp the "is the log current" section prints. */
    private const val SECOND_STAMP_LENGTH = 14

    /**
     * [lines] cut down to the ones [select] could keep, plus the debugging lines the report lists, newest
     * kept when they exceed [maxChars]. Streams: holds only what it keeps, so a 60 MB log costs no more
     * than the budget. Pids are not windowed here (a superset); [select] windows them in the app.
     */
    fun prefilter(lines: Sequence<String>, ours: Ours, maxChars: Int): String {
        val namesUs = namesUsMatcher(ours)
        val ownPids = ours.pidWindows.mapTo(HashSet()) { it.pid }
        val kept = ArrayDeque<String>()
        var chars = 0
        var read = 0
        var candidates = 0
        var oldest: String? = null
        var newest: String? = null
        var group: Parsed? = null
        var groupSize = 0
        for (line in lines) {
            val p = parse(line) ?: continue
            read++
            val stamp = line.take(SECOND_STAMP_LENGTH)
            if (oldest == null) oldest = stamp
            newest = stamp
            if (p.tag.startsWith(OWN_TAG_PREFIX)) ownPids += p.pid
            val startsGroup = p.tag in SYSTEM_TAGS && NOTICE_FAILURE.containsMatchIn(line)
            val inGroup = !startsGroup && group?.let {
                it.pid == p.pid && it.tid == p.tid && it.tag == p.tag && groupSize < MAX_GROUP_LINES
            } == true
            when {
                startsGroup -> { group = p; groupSize = 1 }
                inGroup -> groupSize++
                else -> group = null
            }
            val candidate = startsGroup || inGroup || keep(line) || p.pid in ownPids ||
                (isSystemTag(p.tag) && namesUs(line)) || LogcatHealth.isDebuggingSystemLine(line)
            if (!candidate) continue
            candidates++
            kept.addLast(line)
            chars += line.length + 1
            while (chars > maxChars && kept.size > 1) chars -= kept.removeFirst().length + 1
        }
        val summary = "$PREFILTER_MARK|read=$read|oldest=${oldest.orEmpty()}|newest=${newest.orEmpty()}" +
            "|truncated=${candidates - kept.size}"
        return summary + "\n" + kept.joinToString("\n")
    }

    /** The parts of a [prefilter] answer, or null when [text] is something else — a plain logcat dump. */
    fun parsePrefiltered(text: String): Prefiltered? {
        val first = text.substringBefore('\n')
        if (!first.startsWith("$PREFILTER_MARK|")) return null
        val fields = first.split('|').drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return Prefiltered(
            read = fields["read"]?.toIntOrNull() ?: 0,
            oldest = fields["oldest"]?.ifEmpty { null },
            newest = fields["newest"]?.ifEmpty { null },
            truncated = fields["truncated"]?.toIntOrNull() ?: 0,
            lines = text.substringAfter('\n', "").split('\n').filter { it.isNotBlank() },
        )
    }

    /**
     * [ours] as it crosses the binder to the recorder: digits and separators only (`uid;pid@from-to,…`).
     * The package is not sent; the recorder runs from the same APK and knows it.
     */
    fun encodeOurs(ours: Ours): String =
        ours.uid.toString() + ours.pidWindows.takeIf { it.isNotEmpty() }
            ?.joinToString(",", prefix = ";") { "${it.pid}@${it.fromMs}-${it.toMs}" }.orEmpty()

    /** [encodeOurs] read back, or null for anything that is not exactly that shape. */
    fun decodeOurs(arg: String?, packageName: String): Ours? {
        val m = arg?.let { OURS_ARG.matchEntire(it) } ?: return null
        val windows = m.groupValues[2].takeIf { it.isNotEmpty() }?.split(',')?.map { w ->
            val (pid, range) = w.split('@')
            PidWindow(pid.toInt(), range.substringBefore('-').toLong(), range.substringAfter('-').toLong())
        }.orEmpty()
        return Ours(packageName, m.groupValues[1].toInt(), windows)
    }

    private val OURS_ARG =
        Regex("""^(\d{1,9})(?:;(\d{1,7}@\d{1,13}-\d{1,13}(?:,\d{1,7}@\d{1,13}-\d{1,13}){0,63}))?$""")

    private const val PER_USER_RANGE = 100_000
    private const val FIRST_APPLICATION_UID = 10_000
    private const val STAMP_LENGTH = 18

    private val STAMP = Regex("""^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})""")

    private val HEADER =
        Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+(\d+)\s+[VDIWEFS]\s+(\S.{0,63}?):\s""")

    private val THREADTIME =
        Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEFS]\s+(\S.{0,63}?):\s""")
}
