/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.system.diagnostics.SystemLogCollector
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads the battery numbers for the debug report when the report is built — with debug logging on or off.
 *
 * The recorder runs the fixed `battery_stats` dump and cuts it to our uids; with no recorder, standalone mode falls
 * back to the app's own ADB shell (also the shell user), and Shizuku mode has no fallback by design. The whole
 * read is bounded by [BUDGET_MS] so a wedged link costs the report seconds, never the report.
 */
object BatteryUseCollector {

    private const val TAG = "CV:BatteryUse"

    /** The recorder's own bound is 15 s; this leaves room for the binder round trip or an ADB connect. */
    const val BUDGET_MS = 20_000L

    /** The dump's text, or why there is none. */
    data class Fetched(val text: String?, val whyMissing: String?)

    /** The section's lines, heading included, and the gap to name on the "Missing" line when it could not be read. */
    data class Section(val lines: List<String>, val gap: String?)

    /**
     * The section for the report. [logEntries] is the app's debug log when logging is on, else null.
     * [fetch] is replaceable for tests; it runs on its own scope so a blocking read past the budget is abandoned.
     */
    suspend fun collect(
        context: Context,
        logEntries: List<String>?,
        budgetMs: Long = BUDGET_MS,
        fetch: suspend () -> Fetched = { fetchDump(context) },
    ): Section {
        val scope = CoroutineScope(Dispatchers.IO)
        val fetched = try {
            val pending = scope.async { fetchOrWhy(fetch) }
            withTimeoutOrNull(budgetMs) { pending.await() } ?: Fetched(null, "not collected within ${budgetMs / 1000} s")
        } finally {
            // On the budget running out AND on the report itself being cancelled: the read must not outlive it.
            scope.cancel()
        }
        return section(fetched, context.packageName, context.applicationInfo.uid, logEntries)
    }

    /** [fetch]'s answer, or a failure named for the report; a cancellation is passed on, never reported as a failure. */
    private suspend fun fetchOrWhy(fetch: suspend () -> Fetched): Fetched = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.w(TAG, "Battery read failed: ${e.javaClass.simpleName}: ${e.message}")
        Fetched(null, "reading it failed: ${e.javaClass.simpleName}")
    }

    /**
     * Pure: the section for [fetched]. The recorder's uid comes from its cut's header (one binder call, not two);
     * a raw answer came from the app's ADB shell, which is the shell user.
     */
    fun section(fetched: Fetched, packageName: String, appUid: Int, logEntries: List<String>?): Section {
        val text = fetched.text ?: return missing(fetched.whyMissing ?: "batterystats gave nothing")
        val recorderUid = BatteryCheckin.recorderUidOf(text) ?: BatteryCheckin.SHELL_UID
        val use = BatteryUseParser.parse(text, packageName, recorderUid, appUid)
            ?: return missing("batterystats answered without the since-charge numbers: refused or not a checkin dump")
        val counts = logEntries?.let { RecorderActivityCounts.count(it, use.sinceMillis) }
        return Section(listOf(BatteryUseReport.HEADING) + BatteryUseReport.lines(use, counts), gap = null)
    }

    private fun missing(why: String) = Section(
        lines = listOf(BatteryUseReport.HEADING, "Not available: $why."),
        gap = "battery use ($why)",
    )

    private fun fetchDump(context: Context): Fetched {
        val connected = RecorderConnection.service != null
        val shizuku = runCatching { AppPreferences(context).getPrivilegedMode().needsShizuku }.getOrDefault(false)
        if (!connected && shizuku) return Fetched(null, "recorder not connected, and Shizuku mode has no ADB fallback")
        val text = SystemLogCollector.dump(context, "battery_stats", "dumpsys batterystats -c ${context.packageName}")
        if (!text.isNullOrBlank()) return Fetched(text, null)
        AppLogger.w(TAG, "No batterystats answer (recorder connected: $connected)")
        val why = if (connected) "batterystats gave nothing: timed out, refused, or a recorder older than this app" else "recorder not connected, and the ADB shell gave nothing"
        return Fetched(null, why)
    }
}
