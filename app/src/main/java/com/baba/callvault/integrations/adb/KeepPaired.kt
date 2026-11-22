/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import android.provider.Settings
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.SharedStatusNotice
import com.baba.callvault.utils.AppLogger

/** What the user answered to "Stay paired for good?". */
enum class KeepPairedAnswer { NOT_ASKED, STAY_PAIRED, KEEP_LIMIT }

/** The global setting `adb_allowed_connection_time`, behind a seam so the logic can be tested. */
interface AdbConnectionLimit {
    /** The limit in milliseconds, or null when it cannot be read (logged by the implementation). */
    fun read(): Long?

    /** Writes the limit; false when Android refused (logged by the implementation). */
    fun write(ms: Long): Boolean
}

/** What CallVault remembers about this feature: the answer, and the value it replaced (null = it never set it). */
interface KeepPairedMemory {
    fun answer(): KeepPairedAnswer
    fun setAnswer(answer: KeepPairedAnswer)
    fun originalMs(): Long?
    fun setOriginalMs(ms: Long?)
}

/**
 * When to ask "Stay paired for good?" — once, in standalone mode, with the pairing in place. Pure: every
 * input is passed in, and every skip has a named reason so the caller can log it.
 */
object KeepPairedPolicy {
    data class Situation(
        val isStandalone: Boolean,
        val isPaired: Boolean,
        val hasSecureSettingsGrant: Boolean,
        val answer: KeepPairedAnswer,
        val isRecordingLive: Boolean,
        /** The current limit, or null when unreadable. */
        val limitMs: Long?,
    )

    enum class Decision(val ask: Boolean) {
        ASK(true),
        SKIP_SHIZUKU_MODE(false),
        SKIP_NOT_PAIRED(false),
        SKIP_NO_GRANT(false),
        SKIP_ALREADY_ANSWERED(false),
        SKIP_RECORDING(false),
        SKIP_UNREADABLE(false),
        SKIP_ALREADY_OFF(false),
    }

    fun decide(s: Situation): Decision = when {
        !s.isStandalone -> Decision.SKIP_SHIZUKU_MODE
        !s.isPaired -> Decision.SKIP_NOT_PAIRED
        !s.hasSecureSettingsGrant -> Decision.SKIP_NO_GRANT
        s.answer != KeepPairedAnswer.NOT_ASKED -> Decision.SKIP_ALREADY_ANSWERED
        s.isRecordingLive -> Decision.SKIP_RECORDING
        s.limitMs == null -> Decision.SKIP_UNREADABLE
        s.limitMs == 0L -> Decision.SKIP_ALREADY_OFF
        else -> Decision.ASK
    }
}

/**
 * Switches Android's 7-day "forget the ADB pairing" limit off (value 0) and back, with the user's consent.
 *
 * Only a value CallVault itself replaced is ever put back: the original is stored when we write, and
 * [restore] does nothing without it (memory rule "only undo switches we set").
 */
object KeepPaired {
    private const val TAG = "CV:KeepPaired"
    const val SETTING = "adb_allowed_connection_time"
    const val ANDROID_DEFAULT_MS = 604_800_000L

    enum class Outcome { DONE, UNREADABLE, WRITE_FAILED, ALREADY_OFF, NOT_OURS }

    private val defaultLog: (String) -> Unit = { AppLogger.i(TAG, it) }

    fun stayPaired(limit: AdbConnectionLimit, memory: KeepPairedMemory, log: (String) -> Unit = defaultLog): Outcome {
        val before = limit.read()
        if (before == null) {
            log("Stay paired: the limit cannot be read, so nothing was changed")
            return Outcome.UNREADABLE
        }
        if (before == 0L) {
            log("Stay paired: the limit already reads 0 and CallVault did not set it, so it is left alone")
            return Outcome.ALREADY_OFF
        }
        if (!limit.write(0L)) {
            log("Stay paired: Android refused the write (limit stays $before ms); the pairing can still expire")
            return Outcome.WRITE_FAILED
        }
        memory.setOriginalMs(before)
        memory.setAnswer(KeepPairedAnswer.STAY_PAIRED)
        log("Stay paired: limit changed from $before ms to ${limit.read() ?: 0L} ms (0 = never expires); original kept for the switch in Settings")
        return Outcome.DONE
    }

    fun keepLimit(memory: KeepPairedMemory, log: (String) -> Unit = defaultLog) {
        memory.setAnswer(KeepPairedAnswer.KEEP_LIMIT)
        log("Keep the limit: the user chose to leave Android's pairing time limit as it is; not asked again")
    }

    fun restore(limit: AdbConnectionLimit, memory: KeepPairedMemory, log: (String) -> Unit = defaultLog): Outcome {
        val original = memory.originalMs()
        if (original == null) {
            log("Restore: CallVault did not set the limit, so it is not touched")
            return Outcome.NOT_OURS
        }
        val before = limit.read()
        if (!limit.write(original)) {
            log("Restore: Android refused the write; the original $original ms is kept to try again")
            return Outcome.WRITE_FAILED
        }
        memory.setOriginalMs(null)
        memory.setAnswer(KeepPairedAnswer.KEEP_LIMIT)
        log("Restore: limit changed from ${before ?: "unreadable"} ms back to $original ms")
        return Outcome.DONE
    }

    /** True only when the setting provably reads 0; an unreadable setting is never shown as on. */
    fun isOn(limit: AdbConnectionLimit): Boolean = limit.read() == 0L

    /** Whether the switch may be turned off: only when CallVault replaced the value. */
    fun canRestore(memory: KeepPairedMemory): Boolean = memory.originalMs() != null

    /** Logged when the mode switches to Shizuku: the setting stays as it is. */
    fun logLeftAsIs(limit: AdbConnectionLimit, memory: KeepPairedMemory, log: (String) -> Unit = defaultLog) {
        log(
            "Switching to Shizuku mode: the pairing time limit is left as it is (reads ${limit.read() ?: "unreadable"}, " +
                "set by CallVault: ${canRestore(memory)}); Shizuku mode does not need the pairing",
        )
    }

    /** The real setting. Failures are logged here, with the reason, and never thrown. */
    fun systemLimit(context: Context): AdbConnectionLimit = object : AdbConnectionLimit {
        override fun read(): Long? = try {
            Settings.Global.getLong(context.contentResolver, SETTING, ANDROID_DEFAULT_MS)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Cannot read $SETTING: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        override fun write(ms: Long): Boolean = try {
            Settings.Global.putLong(context.contentResolver, SETTING, ms)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Cannot write $SETTING: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun memory(context: Context): KeepPairedMemory {
        val prefs = AppPreferences(context)
        return object : KeepPairedMemory {
            override fun answer() = prefs.getKeepPairedAnswer()
            override fun setAnswer(answer: KeepPairedAnswer) = prefs.setKeepPairedAnswer(answer)
            override fun originalMs() = prefs.getKeepPairedOriginalMs()
            override fun setOriginalMs(ms: Long?) = prefs.setKeepPairedOriginalMs(ms)
        }
    }

    /** Whether to ask now, and (logged) why not. Cheap enough for Home's resume. */
    fun shouldAsk(context: Context): Boolean {
        val prefs = AppPreferences(context)
        val situation = KeepPairedPolicy.Situation(
            isStandalone = !prefs.getPrivilegedMode().needsShizuku,
            isPaired = prefs.isAdbPaired(),
            hasSecureSettingsGrant = AdbShell.hasWriteSecureSettings(context),
            answer = prefs.getKeepPairedAnswer(),
            isRecordingLive = isRecordingLive(),
            limitMs = systemLimit(context).read(),
        )
        val decision = KeepPairedPolicy.decide(situation)
        if (!decision.ask && decision != KeepPairedPolicy.Decision.SKIP_ALREADY_ANSWERED) {
            AppLogger.d(TAG, "Not asking 'Stay paired for good?': $decision")
        }
        return decision.ask
    }

    /** A call is being recorded: the daemon says so, or a recording owns the shared notification. */
    internal fun isRecordingLive(): Boolean =
        runCatching { RecorderConnection.service?.isRecording == true }.getOrDefault(false) ||
            SharedStatusNotice.isHeldByRecording
}
