/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * What to do with one finished call when after-call transcription is limited to one SIM (issue #81).
 *
 * [targetSlot] is the 1-based slot the user chose, or null for every SIM (the default, and the
 * behaviour of every install that predates the setting). [resolvedSlot] is what the call log said,
 * or null when it could not be told. An unknown SIM is transcribed and kept: dropping a call
 * because the log was late, or the permission missing, would lose a recording the user never
 * asked to lose.
 *
 * A slot that is not the chosen one is not transcribed. The recording is always kept:
 * transcription settings never delete a recording. [otherDeletes] is ignored and remains only
 * so an older stored choice cannot change this.
 */
object AfterCallSimPolicy {

    data class Decision(val transcribe: Boolean, val deleteRecording: Boolean)

    fun decide(targetSlot: Int?, @Suppress("UNUSED_PARAMETER") otherDeletes: Boolean, resolvedSlot: Int?): Decision {
        val transcribe = targetSlot == null || resolvedSlot == null || resolvedSlot == targetSlot
        return Decision(transcribe = transcribe, deleteRecording = false)
    }
}

/** What happens to a recording of a call on a SIM other than the one selected for transcription. */
enum class OtherSimAction(val key: String) {
    KEEP("keep"),
    DELETE("delete");

    companion object {
        fun fromKey(key: String?): OtherSimAction = entries.firstOrNull { it.key == key } ?: KEEP
    }
}
