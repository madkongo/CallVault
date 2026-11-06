/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.os.SystemClock
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.utils.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rules for noticing that the phone has paused CallVault's process, kept free of Android so they are tested.
 *
 * Why: on an Honor BVL-N59 (MagicOS 11 / Android 17, 2026-11-05) the app stopped running every time the screen
 * went dark during a call, until it came back on, while the recorder process carried on. One call was lost and
 * one was cut to 10%. Launch settings were already "run in background", so the user cannot fix it.
 */
object AppPausePolicy {
    /** How often the watch wakes. It never wakes a sleeping phone: Thread.sleep does not run in deep sleep. */
    const val TICK_MS = 3_000L

    /** Extra uptime across one tick that counts as a pause. A busy or GC-ing process loses milliseconds, not this. */
    const val MIN_PAUSE_MS = 10_000L

    /** How long one seen pause keeps calls on the recorder process's own capture. A ROM update can end the pausing. */
    const val REMEMBER_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * How long the process was held still across one tick, or 0. Uses uptime, which stops in deep sleep, so
     * an ordinary sleeping phone never reads as a pause. Only a process that was stopped while the phone ran does.
     */
    fun pausedMs(uptimeBefore: Long, uptimeAfter: Long, tickMs: Long = TICK_MS): Long {
        val extra = uptimeAfter - uptimeBefore - tickMs
        return if (extra >= MIN_PAUSE_MS) extra else 0L
    }

    /** Whether recordings should avoid the path the app itself must keep feeding (handoff). */
    fun preferRecorderCapture(lastPauseAtMs: Long, nowMs: Long): Boolean =
        lastPauseAtMs > 0 && nowMs - lastPauseAtMs in 0 until REMEMBER_MS
}

/**
 * Watches for the phone pausing CallVault's process and remembers when it last did. Runs while the keep-alive
 * service runs. Nothing changes on a phone that never pauses the app.
 */
object AppPauseWatch {
    private const val TAG = "CV:AppPause"
    private const val PREFS = "cv_app_pause"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_COUNT = "count"
    private const val KEY_LONGEST_MS = "longest_ms"

    private var thread: Thread? = null

    @Synchronized
    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        thread = Thread {
            var before = SystemClock.uptimeMillis()
            while (true) {
                try {
                    Thread.sleep(AppPausePolicy.TICK_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val after = SystemClock.uptimeMillis()
                val paused = AppPausePolicy.pausedMs(before, after)
                before = after
                if (paused > 0) runCatching { record(app, paused) }
                    .onFailure { AppLogger.w(TAG, "Could not record a pause: ${it.message}") }
            }
        }.apply { isDaemon = true; name = "cv-app-pause-watch"; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    /** Whether the next recording should be captured by the recorder process rather than handed to the app. */
    fun preferRecorderCapture(context: Context): Boolean =
        AppPausePolicy.preferRecorderCapture(lastPauseAt(context), System.currentTimeMillis())

    fun lastPauseAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_LAST_AT, 0L) }.getOrDefault(0L)

    /** One line for the debug report header. */
    fun summaryForReport(context: Context): String {
        val p = prefs(context)
        val count = p.getInt(KEY_COUNT, 0)
        if (count == 0) return "none seen"
        val last = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(p.getLong(KEY_LAST_AT, 0L)))
        return "$count, longest ${p.getLong(KEY_LONGEST_MS, 0L) / 1000}s, last $last"
    }

    private fun record(context: Context, pausedMs: Long) {
        val now = System.currentTimeMillis()
        val p = prefs(context)
        p.edit()
            .putLong(KEY_LAST_AT, now)
            .putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1)
            .putLong(KEY_LONGEST_MS, maxOf(p.getLong(KEY_LONGEST_MS, 0L), pausedMs))
            .apply()
        val seconds = pausedMs / 1000
        AppLogger.w(TAG, "The phone paused CallVault for ${seconds}s; calls now record through the recorder process")
        TransportTimeline.event(context, "app paused by the phone for ${seconds}s")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
