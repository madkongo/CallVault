/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import com.baba.callvault.system.diagnostics.battery.RecorderActivityCounts.LogCounts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The "Battery use" section of a debug report: [BatteryUse] as plain lines a person can read, and a one-line
 * verdict that never claims more than the numbers show.
 */
object BatteryUseReport {

    const val HEADING = "--- Battery use (since last full charge) ---"

    // 📐 ESTIMATES, not measured. Chosen to flag only a clear outlier for a background call recorder that sits idle
    // between calls; none comes from a survey of real phones. Revisit once real reports carry this section.
    /** CallVault app + shell CPU per hour on battery above which the verdict says "high" (~2.5% of one core). */
    const val CPU_HIGH_MS_PER_HOUR = 90_000L

    /** Share of the time on battery held awake by app + shell partial wake locks. */
    const val WAKE_HIGH_FRACTION = 0.05

    /** The app's wake-up alarms per hour on battery. */
    const val ALARMS_HIGH_PER_HOUR = 12

    /** App + shell share of Android's own drain estimate. */
    const val POWER_SHARE_HIGH = 0.10

    /** Recorder start attempts per hour on battery, from the debug log. */
    const val STARTS_HIGH_PER_HOUR = 2

    /** Shorter than this on battery and nothing is judged: a few minutes of a call skews every rate. */
    const val MIN_PERIOD_MS = 3_600_000L

    const val NORMAL = "looks normal by these numbers"

    private const val APP_LABEL = "CallVault app"
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L
    private const val SECOND_MS = 1_000L
    private const val KB = 1_000L
    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L
    private const val PERCENT = 100

    /** The section's lines, without its heading. [counts] is null when debug logging is off. */
    fun lines(use: BatteryUse, counts: LogCounts?, zone: TimeZone = TimeZone.getDefault()): List<String> = buildList {
        add(period(use, zone))
        add(uidLine(APP_LABEL, use.app))
        add(uidLine("Android System / shell uid ${BatteryCheckin.SHELL_UID} (the recorder, adbd, and any other adb or Shizuku tool)", use.shell))
        use.root?.let { add(uidLine("Root uid 0 (the recorder runs as root; also every other root daemon)", it)) }
        use.app?.topWakeLocks?.takeIf { it.isNotEmpty() }?.let { top ->
            add("Longest CallVault wake locks: " + top.joinToString("; ") { "${it.name} ${it.count}x ${duration(it.ms)}" })
        }
        add(countsLine(counts, use.sinceMillis, zone))
        add("Transcription on battery vs charger: not recorded by this version.")
        use.notes.forEach { add("Note: $it") }
        add("Verdict: " + verdict(use, counts))
    }

    /** One plain verdict: "looks normal…", "too little time…", or "high: <each reason>". */
    fun verdict(use: BatteryUse, counts: LogCounts?): String {
        if (use.onBatteryMs < MIN_PERIOD_MS) return "too little time on battery since the last full charge to judge (${duration(use.onBatteryMs)})"
        val hours = use.onBatteryMs.toDouble() / HOUR_MS
        val reasons = listOfNotNull(
            starts(counts, hours),
            cpu(use, hours),
            wake(use),
            alarms(use, hours),
            power(use),
        )
        return if (reasons.isEmpty()) NORMAL else "high: " + reasons.joinToString("; ")
    }

    private fun starts(counts: LogCounts?, hours: Double): String? {
        val starts = counts?.let { maxOf(it.launchAttempts, it.relaunches) } ?: return null
        return if (starts > STARTS_HIGH_PER_HOUR * hours) "$starts recorder start attempts in the log since the charge" else null
    }

    private fun cpu(use: BatteryUse, hours: Double): String? {
        val app = use.app?.cpuMs ?: 0
        val shell = use.shell?.cpuMs ?: 0
        if (app + shell <= CPU_HIGH_MS_PER_HOUR * hours) return null
        return "CPU ${duration(app + shell)} in ${duration(use.onBatteryMs)} on battery " +
            "(app ${duration(app)}, shell ${duration(shell)}; shell also counts adbd and any other adb tool)"
    }

    private fun wake(use: BatteryUse): String? {
        val held = (use.app?.partialWakeLockMs ?: 0) + (use.shell?.partialWakeLockMs ?: 0)
        if (held <= WAKE_HIGH_FRACTION * use.onBatteryMs) return null
        return "wake locks held ${duration(held)} (${held * PERCENT / use.onBatteryMs}% of the time on battery)"
    }

    private fun alarms(use: BatteryUse, hours: Double): String? {
        val alarms = use.app?.wakeupAlarms ?: 0
        return if (alarms > ALARMS_HIGH_PER_HOUR * hours) "$alarms wake-up alarms" else null
    }

    private fun power(use: BatteryUse): String? {
        val total = use.computedDrainMah?.takeIf { it > 0 } ?: return null
        val ours = (use.app?.powerMah ?: 0.0) + (use.shell?.powerMah ?: 0.0)
        if (ours < POWER_SHARE_HIGH * total) return null
        return "estimated ${"%.0f".format(Locale.US, ours)} mAh of Android's ${"%.0f".format(Locale.US, total)} mAh estimate " +
            "(${(ours * PERCENT / total).toInt()}%)"
    }

    private fun period(use: BatteryUse, zone: TimeZone): String = buildString {
        append("Period: ").append(duration(use.onBatteryMs)).append(" on battery")
        val screen = listOfNotNull(
            use.screenOffMs?.let { "screen off ${duration(it)}" },
            use.screenOnMs?.let { "screen on ${duration(it)}" },
        )
        if (screen.isNotEmpty()) append(" (").append(screen.joinToString(", ")).append(')')
        if (use.dropLowPercent != null && use.dropHighPercent != null) {
            append(", battery dropped ")
            append(if (use.dropLowPercent == use.dropHighPercent) "${use.dropLowPercent}" else "${use.dropLowPercent}-${use.dropHighPercent}")
            append('%')
        }
        use.sinceMillis?.let { append(", since ").append(clock(it, zone)) }
        // Zero is "not computed" (seen with the battery reported as unplugged), not a measurement: left out.
        use.computedDrainMah?.takeIf { it > 0 }?.let { append("; Android's estimate of all drain: ").append("%.0f".format(Locale.US, it)).append(" mAh") }
        append('.')
    }

    private fun uidLine(label: String, use: UidUse?): String {
        val isApp = label == APP_LABEL
        if (use == null) return "$label: no row in batterystats (nothing measurable, or the uid was not found)."
        val parts = buildList {
            add("CPU ${duration(use.cpuMs)}")
            add("wake locks ${use.partialWakeLockCount} (${duration(use.partialWakeLockMs)})")
            add("alarms ${use.wakeupAlarms}")
            add("jobs ${use.jobCount} (${duration(use.jobMs)})")
            use.foregroundServiceMs?.let { add("foreground service ${duration(it)}") }
            add("data mobile ${bytes(use.mobileRxBytes + use.mobileTxBytes)} / Wi-Fi ${bytes(use.wifiRxBytes + use.wifiTxBytes)}")
            if (use.mobileRadioActiveMs > 0) add("mobile radio active ${duration(use.mobileRadioActiveMs)}")
            use.powerMah?.let { add("est. ${"%.1f".format(Locale.US, it)} mAh") }
            // Process rows are Android's app processes only: under the shell uid they are com.android.shell, never the
            // recorder (app_process started over ADB), so they would mislead there.
            if (isApp && use.processStarts > 0) add("process starts ${use.processStarts}")
            if (isApp && use.processCrashes > 0) add("crashes ${use.processCrashes}")
            if (isApp && use.processAnrs > 0) add("ANRs ${use.processAnrs}")
        }
        val name = if (isApp) "$label (uid ${use.uid})" else label
        return "$name: " + parts.joinToString(", ")
    }

    private fun countsLine(counts: LogCounts?, sinceMillis: Long?, zone: TimeZone): String {
        if (counts == null) return "From CallVault's own log: not counted (debug logging is off)."
        val head = "From CallVault's own log since then: ${counts.launchAttempts} recorder launch attempts, " +
            "${counts.relaunches} keep-alive relaunches, ${counts.adbRebuilds} ADB connection rebuilds."
        val logStart = counts.logStartMillis
        val isShorter = logStart != null && sinceMillis != null && logStart > sinceMillis
        return if (isShorter) "$head (the log only goes back to ${clock(logStart!!, zone)})" else head
    }

    private fun clock(millis: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone }.format(Date(millis))

    /** `0s`, `<1s`, `59s`, `2m10s`, `6h00m`. */
    fun duration(ms: Long): String {
        val h = ms / HOUR_MS
        val m = ms % HOUR_MS / MINUTE_MS
        val s = ms % MINUTE_MS / SECOND_MS
        return when {
            ms in 1 until SECOND_MS -> "<1s"
            h > 0 -> "%dh%02dm".format(Locale.US, h, m)
            m > 0 -> "%dm%02ds".format(Locale.US, m, s)
            else -> "${s}s"
        }
    }

    /** `0 B`, `900 KB`, `1.5 MB`, decimal units. */
    fun bytes(n: Long): String = when {
        n >= GB -> "%.1f GB".format(Locale.US, n.toDouble() / GB)
        n >= MB -> "%.1f MB".format(Locale.US, n.toDouble() / MB)
        n >= KB -> "${n / KB} KB"
        else -> "$n B"
    }
}
