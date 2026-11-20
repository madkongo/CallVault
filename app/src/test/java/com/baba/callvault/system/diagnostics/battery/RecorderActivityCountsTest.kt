/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** Counts of what the app already logs — launches, relaunches, ADB rebuilds — inside the battery period. */
class RecorderActivityCountsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-11-20 08:00:00 UTC
    private val since = 1_795_161_600_000L

    private val log = listOf(
        "2026-11-20 07:59:59.000 [I] CV:RecorderLauncher: Attempt 1: launching recorder daemon. apk=/data/app/x",
        "2026-11-20 08:00:01.000 [I] CV:RecorderLauncher: Attempt 1: launching recorder daemon. apk=/data/app/x",
        "2026-11-20 08:00:02.000 [I] CV:DaemonKeepAlive: keep-alive: daemon down — relaunching (force=false offline=false)",
        "2026-11-20 08:10:00.000 [I] CV:DaemonKeepAlive: keep-alive: binder-death signal — relaunching immediately",
        "2026-11-20 08:11:00.000 [W] CV:DaemonKeepAlive: keep-alive: relaunch keeps timing out — rebuilding the ADB connection",
        "2026-11-20 09:00:00.000 [I] CV:Other: a message that says launching recorder daemon in passing",
        "not a log line",
    )

    @Test
    fun `only lines inside the period and from the right tags are counted`() {
        val counts = RecorderActivityCounts.count(log, since, utc)
        assertEquals(1, counts.launchAttempts)
        assertEquals(2, counts.relaunches)
        assertEquals(1, counts.adbRebuilds)
    }

    @Test
    fun `the log's first entry is reported so a short log is not mistaken for a quiet one`() {
        assertEquals(since - 1_000, RecorderActivityCounts.count(log, since, utc).logStartMillis)
        assertNull(RecorderActivityCounts.count(emptyList(), since, utc).logStartMillis)
    }

    @Test
    fun `with no period start everything in the log counts`() {
        assertEquals(2, RecorderActivityCounts.count(log, null, utc).launchAttempts)
    }
}
