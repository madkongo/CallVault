/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import android.content.Context
import android.os.SystemClock
import com.baba.callvault.integrations.adb.UsbDebuggingEvents
import com.baba.callvault.utils.AppLogger

/**
 * An always-on record of what happens to a recording at the end of a call, written whether or not debug
 * logging is on, into the same file as [com.baba.callvault.integrations.adb.TransportTimeline] (and so into
 * every debug report, under "USB debugging switched off (always on)").
 *
 * Why (BabApps item 69, GitHub #63): a 51-minute call was saved to the folder but never reached the library, and
 * with logging off nothing could say whether the catalog write ran, failed, or never started, whether the
 * duration probe stalled, or which capture path made the file. Logging only: nothing here changes what happens.
 *
 * No phone numbers and no contact names: a file name is cut down to its timestamp, direction marker and
 * extension by [safeName], and the whole line is then passed through [AppLogger.redactForReport].
 */
object CallEndLog {

    /** A list-row duration probe is only worth a line when it takes longer than this. */
    const val SLOW_ROW_PROBE_MS = 2_000L

    private val STAMP = Regex("""\d{8}_\d{6}\.\d{3}(?:[+-]|%2[Bb])\d{4}|(?<!\d)${com.baba.callvault.utils.FileNameStamp.PAIR_PATTERN}(?!\d)""")
    private val MARKER = Regex("""^_(?:in|out|import(?:_transcribeonly)?|voip(?:-[^_\s.]+)?)(?=[_.\s-]|%20)""")
    private val EXTENSION = Regex("""\.([A-Za-z0-9]{1,5})$""")

    /**
     * The recording's name with the person left out: its timestamp, its direction marker (`_in`, `_out`,
     * `_voip-App`) and its extension. A name in a template with no timestamp becomes just `?` plus the extension.
     */
    fun safeName(name: String): String {
        val extension = EXTENSION.find(name)?.value.orEmpty()
        val stamp = STAMP.find(name) ?: return "?$extension"
        val marker = MARKER.find(name.substring(stamp.range.last + 1))?.value.orEmpty()
        return stamp.value + marker + extension
    }

    /** How a failure reads in a line: its class and a short message, never a stack. */
    fun describe(failure: Throwable): String =
        failure.javaClass.simpleName + (failure.message?.let { ": " + it.take(MAX_MESSAGE).replace('\n', ' ') } ?: "")

    private const val MAX_MESSAGE = 120

    /** The end-of-call catalog write is about to start. */
    fun catalogStart(name: String, sizeBytes: Long): String =
        "catalog write start: ${safeName(name)} size=$sizeBytes"

    /** The end-of-call catalog write finished: [failure] null means the row was written. */
    fun catalogEnd(name: String, sizeBytes: Long, elapsedMs: Long, failure: Throwable?): String =
        if (failure == null) "catalog write done: ${safeName(name)} size=$sizeBytes elapsed=${elapsedMs}ms"
        else "catalog write FAILED: ${safeName(name)} size=$sizeBytes elapsed=${elapsedMs}ms ${describe(failure)}"

    /** The end-of-call duration probe is about to start (only runs when a minimum duration is set). */
    fun probeStart(path: String, name: String): String =
        "end-of-call duration probe start ($path): ${safeName(name)}"

    /** The end-of-call duration probe ended. A result of 0 means the container declared no duration, or the read failed. */
    fun probeEnd(path: String, name: String, elapsedMs: Long, durationMs: Long): String =
        "end-of-call duration probe end ($path): ${safeName(name)} elapsed=${elapsedMs}ms result=${durationMs}ms"

    /** The capture path a call is using. */
    fun capturePath(path: String, mode: String, source: String, codec: String): String =
        "capture path: $path mode=$mode source=$source codec=$codec"

    /** A list-row duration probe that took long; null when it was not slow enough to be worth a line. */
    fun slowRowProbe(name: String, fileBytes: Long, elapsedMs: Long, durationMs: Long): String? =
        if (elapsedMs <= SLOW_ROW_PROBE_MS) null
        else "slow list duration probe: ${safeName(name)} length=$fileBytes elapsed=${elapsedMs}ms result=${durationMs}ms"

    /**
     * The capture path's name, from the flags the engine already keeps. [expectsDirect] is what the app
     * expects of the daemon: it falls back to scrcpy when the direct path cannot start, and the daemon's own
     * log never reaches this process, so this says what was asked for.
     */
    fun capturePathName(appCallMix: Boolean, handoff: Boolean, daemon: Boolean, expectsDirect: Boolean): String = when {
        appCallMix -> "app-call mix (daemon)"
        handoff -> "handoff (resilient)"
        daemon && expectsDirect -> "daemon direct AudioRecord"
        daemon -> "daemon scrcpy (muxer)"
        else -> "local scrcpy over ADB (muxer)"
    }

    /** [line] with its line breaks turned into spaces: the always-on file holds one entry per line. */
    fun singleLine(line: String): String = line.replace(LINE_BREAKS, " ")

    private val LINE_BREAKS = Regex("[\\r\\n]+")

    /** Writes [line] to the always-on file and to the ordinary log, as one line. Never throws. */
    fun record(context: Context, line: String) {
        runCatching {
            val safe = AppLogger.redactForReport(singleLine(line))
            UsbDebuggingEvents.record(context, "call: $safe")
            AppLogger.i(TAG, safe)
        }
    }

    /** Runs [block], the end-of-call duration probe, with a start and an end line around it. */
    inline fun timedProbe(context: Context, path: String, name: String, block: () -> Long): Long {
        record(context, probeStart(path, name))
        val t0 = SystemClock.elapsedRealtime()
        val result = block()
        record(context, probeEnd(path, name, SystemClock.elapsedRealtime() - t0, result))
        return result
    }

    const val TAG = "CV:CallEnd"
}
