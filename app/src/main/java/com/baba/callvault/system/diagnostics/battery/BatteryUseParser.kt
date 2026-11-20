/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import com.baba.callvault.system.diagnostics.battery.BatteryCheckin.Row

/**
 * Reads a batterystats checkin answer — the recorder's cut, or a raw dump from the app's ADB shell — into [BatteryUse].
 *
 * Field positions follow AOSP `BatteryStats.dumpCheckinLocked` (checkin version 9, unchanged in shape from Android 11
 * to 16). Newer releases only ever append fields, so extra trailing fields are ignored; a missing or non-numeric
 * field reads as zero or null rather than failing the whole section.
 */
object BatteryUseParser {

    /** The per-uid radio-active time in an `nt` row is in microseconds, unlike the other times. */
    private const val MICROS_PER_MILLI = 1_000L

    /** The model, or null when the answer holds no battery row (refused, empty, or not a checkin dump at all). */
    fun parse(text: String, ownPackage: String, recorderUid: Int, knownAppUid: Int?): BatteryUse? {
        val cut = if (BatteryCheckin.isCut(text)) text else BatteryCheckin.cut(text.lineSequence(), ownPackage, recorderUid, knownAppUid)
        val lines = cut.lines()
        val appUid = knownAppUid ?: headerAppUid(lines.firstOrNull())
        val rows = lines.mapNotNull { BatteryCheckin.row(it) }.filter { it.category == "l" }
        val bt = rows.global("bt") ?: return null
        val dc = rows.global("dc")
        val shellUid = if (recorderUid == BatteryCheckin.ROOT_UID) BatteryCheckin.SHELL_UID else recorderUid
        return BatteryUse(
            sinceMillis = bt.long(5).takeIf { it > 0 },
            onBatteryMs = bt.long(1),
            screenOffMs = bt.longOrNull(6),
            screenOnMs = rows.global("m")?.longOrNull(0),
            dropLowPercent = dc?.intOrNull(0),
            dropHighPercent = dc?.intOrNull(1),
            computedDrainMah = rows.global("pws")?.doubleOrNull(1),
            recorderUid = recorderUid,
            app = appUid?.let { uidUse(rows, it) },
            shell = uidUse(rows, shellUid),
            root = if (recorderUid == BatteryCheckin.ROOT_UID) uidUse(rows, BatteryCheckin.ROOT_UID) else null,
            notes = lines.filter { it.startsWith("# ") && !it.startsWith(BatteryCheckin.HEADER) }.map { it.removePrefix("# ") },
        )
    }

    private fun headerAppUid(header: String?): Int? =
        header?.takeIf { it.startsWith(BatteryCheckin.HEADER) }?.substringAfter("app_uid=", "")?.takeWhile { it.isDigit() }?.toIntOrNull()

    /** Device-wide rows are uid 0 with a type root's per-uid rows never use. */
    private fun List<Row>.global(type: String): Row? = firstOrNull { it.uid == BatteryCheckin.ROOT_UID && it.type == type }

    private fun uidUse(rows: List<Row>, uid: Int): UidUse? {
        val mine = rows.filter { it.uid == uid && isPerUid(it) }
        if (mine.isEmpty()) return null
        val cpu = mine.firstOrNull { it.type == "cpu" }
        val nt = mine.firstOrNull { it.type == "nt" }
        val processes = mine.filter { it.type == "pr" }
        return UidUse(
            uid = uid,
            cpuUserMs = cpu?.long(0) ?: 0,
            cpuSystemMs = cpu?.long(1) ?: 0,
            wakeLocks = mine.filter { it.type == "wl" }.mapNotNull { partialWakeLock(it) },
            aggregatedPartialWakeMs = mine.firstOrNull { it.type == "awl" }?.longOrNull(0),
            wakeupAlarms = mine.filter { it.type == "wua" }.sumOf { it.int(1) },
            jobs = mine.filter { it.type == "jb" }.map { NamedUse(it.fields.firstOrNull().orEmpty(), it.int(2), it.long(1)) },
            foregroundServiceMs = mine.firstOrNull { it.type == "fgs" }?.longOrNull(0),
            mobileRxBytes = nt?.long(0) ?: 0,
            mobileTxBytes = nt?.long(1) ?: 0,
            wifiRxBytes = nt?.long(2) ?: 0,
            wifiTxBytes = nt?.long(3) ?: 0,
            mobileRadioActiveMs = (nt?.long(8) ?: 0) / MICROS_PER_MILLI,
            powerMah = mine.firstOrNull { it.type == "pwi" && it.fields.firstOrNull() == "uid" }?.doubleOrNull(1),
            processStarts = processes.sumOf { it.int(4) },
            processAnrs = processes.sumOf { it.int(5) },
            processCrashes = processes.sumOf { it.int(6) },
        )
    }

    /** Root's per-uid rows share uid 0 with the device-wide ones; only these types are per-uid. */
    private fun isPerUid(row: Row): Boolean = when (row.type) {
        "cpu", "wl", "awl", "wua", "jb", "fgs", "nt", "pr" -> true
        "pwi" -> row.fields.firstOrNull() == "uid"
        else -> false
    }

    /**
     * The partial part of a `wl` row: `<name>,<fullMs>,f,<count>,…,<partialMs>,p,<count>,…`. Located by its `p` marker,
     * so older rows without the current/max/total fields read the same.
     */
    private fun partialWakeLock(row: Row): NamedUse? {
        val at = row.fields.indexOf("p").takeIf { it >= 2 } ?: return null
        val ms = row.fields.getOrNull(at - 1)?.toLongOrNull() ?: 0
        val count = row.fields.getOrNull(at + 1)?.toIntOrNull() ?: 0
        return NamedUse(row.fields.first(), count, ms)
    }

    private fun Row.longOrNull(i: Int): Long? = fields.getOrNull(i)?.trim()?.toLongOrNull()
    private fun Row.long(i: Int): Long = longOrNull(i) ?: 0
    private fun Row.intOrNull(i: Int): Int? = fields.getOrNull(i)?.trim()?.toIntOrNull()
    private fun Row.int(i: Int): Int = intOrNull(i) ?: 0
    private fun Row.doubleOrNull(i: Int): Double? = fields.getOrNull(i)?.trim()?.toDoubleOrNull()
}
