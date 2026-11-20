/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

/**
 * What CallVault cost in battery since the last full charge (or the last stats reset), as Android's batterystats
 * counted it. Pure data, free of Android types, so a report section and a future in-app page draw from the same model.
 *
 * Every figure is the platform's own; nothing here is estimated by us except where a field says so.
 *
 * @property sinceMillis wall-clock time the stats period began, or null when the platform did not say.
 * @property onBatteryMs time on battery in the period.
 * @property dropLowPercent / [dropHighPercent] the battery level drop, as the platform brackets it.
 * @property computedDrainMah the platform's own estimate of everything drained, in mAh.
 * @property recorderUid the uid the recorder runs as: 2000 (shell), or 0 under a root Shizuku.
 * @property app the app's own uid; null when batterystats had no row for it.
 * @property shell uid 2000: the recorder AND adbd, and any other adb or Shizuku tool. Settings shows it as "Android System".
 * @property root uid 0, only when the recorder runs as root; it also holds every other root daemon.
 * @property notes what limits this reading (cut short, timed out), in plain words.
 */
data class BatteryUse(
    val sinceMillis: Long?,
    val onBatteryMs: Long,
    val screenOffMs: Long?,
    val screenOnMs: Long?,
    val dropLowPercent: Int?,
    val dropHighPercent: Int?,
    val computedDrainMah: Double?,
    val recorderUid: Int,
    val app: UidUse?,
    val shell: UidUse?,
    val root: UidUse?,
    val notes: List<String>,
)

/**
 * One uid's since-charge cost.
 *
 * @property aggregatedPartialWakeMs the platform's wall-clock partial wake-lock time for the uid (overlaps counted
 *   once), when it reports one; otherwise [partialWakeLockMs] falls back to the per-name sum.
 * @property mobileRadioActiveMs how long this uid kept the cellular radio active.
 * @property powerMah the platform's estimated drain for the uid; null when not reported.
 */
data class UidUse(
    val uid: Int,
    val cpuUserMs: Long = 0,
    val cpuSystemMs: Long = 0,
    val wakeLocks: List<NamedUse> = emptyList(),
    val aggregatedPartialWakeMs: Long? = null,
    val wakeupAlarms: Int = 0,
    val jobs: List<NamedUse> = emptyList(),
    val foregroundServiceMs: Long? = null,
    val mobileRxBytes: Long = 0,
    val mobileTxBytes: Long = 0,
    val wifiRxBytes: Long = 0,
    val wifiTxBytes: Long = 0,
    val mobileRadioActiveMs: Long = 0,
    val powerMah: Double? = null,
    val processStarts: Int = 0,
    val processCrashes: Int = 0,
    val processAnrs: Int = 0,
) {
    val cpuMs: Long get() = cpuUserMs + cpuSystemMs
    val partialWakeLockCount: Int get() = wakeLocks.sumOf { it.count }
    val partialWakeLockMs: Long get() = aggregatedPartialWakeMs ?: wakeLocks.sumOf { it.ms }
    val jobCount: Int get() = jobs.sumOf { it.count }
    val jobMs: Long get() = jobs.sumOf { it.ms }

    /** The partial wake locks that held the phone awake longest, longest first. */
    val topWakeLocks: List<NamedUse> get() = wakeLocks.filter { it.count > 0 || it.ms > 0 }.sortedByDescending { it.ms }.take(TOP)

    private companion object {
        const val TOP = 3
    }
}

/** A named wake lock or job: how many times, and for how long in total. */
data class NamedUse(val name: String, val count: Int, val ms: Long)
