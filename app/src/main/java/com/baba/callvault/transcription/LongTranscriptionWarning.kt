/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import kotlin.math.roundToLong

/**
 * When to tell someone that transcribing a long call on the phone will take a while.
 *
 * There is no length limit. On the phone a run takes about 1.5x the call's length (a real 57:45 call
 * took 92 min on a OnePlus 9 Pro), so a long call started by hand gets one line saying so. A cloud
 * service takes seconds and gets nothing; automatic runs have nobody to tell.
 */
object LongTranscriptionWarning {

    /** Calls this long or longer get the warning. */
    const val THRESHOLD_MINUTES = 30

    private const val THRESHOLD_MS = THRESHOLD_MINUTES * 60_000L

    /** 📐 Run time over call time on the phone, used until this phone has timed a run of its own. */
    const val DEFAULT_SLOWDOWN = 1.5

    /** Whether a run of [audioMs] on the given engine deserves the warning. */
    fun shouldWarn(isCloud: Boolean, audioMs: Long): Boolean = !isCloud && audioMs >= THRESHOLD_MS

    /**
     * The run time to quote: this phone's own measured estimate when it has one, otherwise
     * [DEFAULT_SLOWDOWN] times the call.
     */
    fun estimateMs(audioMs: Long, measuredEstimateMs: Long?): Long =
        measuredEstimateMs?.takeIf { it > 0L } ?: (audioMs * DEFAULT_SLOWDOWN).roundToLong()

    /** Run time over call time, to one decimal, for the "about 1.5x as long" wording. */
    fun slowdown(audioMs: Long, estimateMs: Long): Double {
        if (audioMs <= 0L) return DEFAULT_SLOWDOWN
        return (estimateMs * 10.0 / audioMs).roundToLong() / 10.0
    }

    /** What to ask before a run the user started. */
    enum class Confirmation {
        /** Start straight away. */
        NONE,

        /** The usual battery notice, with its "Don't ask again" box. */
        FULL,

        /**
         * Only the long-call line. Shown when the battery notice was turned off: how long a long call
         * takes is new information the user never declined.
         */
        LONG_CALL_ONLY,
    }

    /**
     * @param isImport an import always confirms, whatever the setting says.
     * @param confirmBeforeRun the battery notice setting ("Don't ask again" turns it off).
     * @param audioMs the call's length, 0 when unknown.
     */
    fun confirmation(
        isCloud: Boolean,
        isImport: Boolean,
        confirmBeforeRun: Boolean,
        audioMs: Long,
    ): Confirmation = when {
        isCloud -> Confirmation.NONE
        isImport || confirmBeforeRun -> Confirmation.FULL
        shouldWarn(isCloud = false, audioMs = audioMs) -> Confirmation.LONG_CALL_ONLY
        else -> Confirmation.NONE
    }
}
