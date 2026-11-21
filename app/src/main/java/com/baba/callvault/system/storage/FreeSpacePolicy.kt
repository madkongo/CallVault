/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.StereoBitRate

/**
 * The rules for the phone's own free space during calls (review R2). Pure: no Android here.
 *
 * Nothing here ever refuses or stops a recording: a partial recording beats none, and there is no mic-only fallback.
 * It only warns early, words the failure right, and writes the number to the always-on log.
 */
object FreeSpacePolicy {

    // Every threshold below is 📐 CALCULATED (arithmetic on bit rates), never measured on a phone.
    // For the default Opus 24 kb/s mono, 5 h is only ~54 MB, so the 300 MB floor is what triggers the warning.

    /** The early warning covers at least this many hours of calls at the user's chosen format. */
    const val WARN_HOURS = 5

    /** ...and never less than this: the system itself needs room, and a short list of calls must not hide a near-full phone. */
    const val WARN_FLOOR_BYTES = 300L * 1024 * 1024

    /** Below this, a recording in progress may have run out of room: a file that stopped growing is cut short. */
    const val CUT_SHORT_BYTES = 20L * 1024 * 1024

    private const val SECONDS_PER_HOUR = 3600L
    private const val BITS_PER_BYTE = 8L
    private const val MB = 1024L * 1024

    /** Free space must climb to this multiple of the threshold before the warning may fire again. */
    private const val REARM_NUM = 3L
    private const val REARM_DEN = 2L

    enum class Action { NONE, WARN, REARM }

    /**
     * Free space worth [WARN_HOURS] hours of calls at [codec], [bitRate] (the user's mono rate) and [stereo], and
     * not under [WARN_FLOOR_BYTES]. An unreadable rate gives the floor.
     */
    fun warnThresholdBytes(codec: ScrcpyAudioCodec, bitRate: Int, stereo: Boolean): Long {
        if (bitRate <= 0) return WARN_FLOOR_BYTES
        val rate = if (stereo) StereoBitRate.floorFor(codec, bitRate) else bitRate
        val bytes = rate.toLong() / BITS_PER_BYTE * SECONDS_PER_HOUR * WARN_HOURS
        return maxOf(WARN_FLOOR_BYTES, bytes)
    }

    /** Warn once per low episode: [alreadyWarned] holds until free space is clearly back. Unknown (-1) does nothing. */
    fun decide(freeBytes: Long, thresholdBytes: Long, alreadyWarned: Boolean): Action = when {
        freeBytes < 0L -> Action.NONE
        freeBytes < thresholdBytes -> if (alreadyWarned) Action.NONE else Action.WARN
        alreadyWarned && freeBytes >= thresholdBytes * REARM_NUM / REARM_DEN -> Action.REARM
        else -> Action.NONE
    }

    /** The phone is full for our purposes: the same line the waiting store uses to refuse a call. */
    fun isStorageFull(freeBytes: Long): Boolean = freeBytes in 0 until WaitingPolicy.MIN_FREE_BYTES

    /** Almost nothing is left: a recording that just ended may have stopped early for lack of room. */
    fun mayHaveBeenCutShort(freeBytes: Long): Boolean = freeBytes in 0 until CUT_SHORT_BYTES

    /** The always-on log line at call start. No names, no numbers. */
    fun callStartLine(free: Long, threshold: Long): String =
        if (free < 0L) "free space at call start: unknown"
        else "free space at call start: ${free / MB} MB (warn below ${threshold / MB} MB)" +
            (if (free < threshold) " LOW" else "")

    /**
     * The capture never started (the muxer or codec refused): when almost nothing is left on the measured volume the
     * cause is the full phone, not the audio format (R2, AAC). Unreadable space or another volume never qualifies.
     */
    fun startFailureIsStorageFull(freeNowBytes: Long, folderOnPhoneStorage: Boolean): Boolean =
        folderOnPhoneStorage && mayHaveBeenCutShort(freeNowBytes)

    enum class EmptyCause { STORAGE_FULL, OTHER }

    /**
     * Why a 0-byte recording ended up empty. The storage wording is used only when the phone was already low at call
     * start AND has almost nothing now AND no daemon death or capture failure is known AND the folder is on the
     * measured volume: otherwise the existing advice stays, so a full phone never hides a real transport fault.
     */
    fun emptyCaptureCause(
        lowAtCallStart: Boolean?,
        freeNowBytes: Long,
        daemonLossKnown: Boolean,
        folderOnPhoneStorage: Boolean,
    ): EmptyCause =
        if (lowAtCallStart == true && mayHaveBeenCutShort(freeNowBytes) && !daemonLossKnown && folderOnPhoneStorage) {
            EmptyCause.STORAGE_FULL
        } else {
            EmptyCause.OTHER
        }

    /**
     * Whether the recording folder is on the volume CallVault measures (the phone's own storage, where staging lives).
     * Unset (null) means the app's default place on the phone. A tree on an SD card, USB drive or a cloud provider is
     * another volume: the wording about a full phone is then not used.
     */
    fun folderOnPhoneStorage(folderUri: String?): Boolean =
        folderUri.isNullOrBlank() ||
            folderUri.contains("com.android.externalstorage.documents/tree/primary%3A", ignoreCase = true) ||
            folderUri.contains("com.android.externalstorage.documents/tree/primary:", ignoreCase = true)
}
