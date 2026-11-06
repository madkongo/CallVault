/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import com.baba.callvault.data.AppPreferences.AfterCallMode
import com.baba.callvault.data.AppPreferences.AfterCallUnanswered
import java.util.Locale

/**
 * The rules of "keep or delete after the call", with no Android in them so each one is unit-tested.
 *
 * See docs/dev-notes/2026-11-06-keep-or-delete-plan.md for the decided behaviour.
 */
object AfterCallPolicy {

    /**
     * Notification ids for the per-recording question: [NOTIFICATION_ID_BASE] + 0..[NOTIFICATION_ID_SPAN]-1.
     *
     * A band of its own, clear of every fixed id CallVault posts (1, 2, 3, 5, 42, 43, 4242, 4712–4724), so a
     * question can never replace a recorder or health notification. Per recording rather than one shared id,
     * because two calls back to back must each keep their own question.
     */
    const val NOTIFICATION_ID_BASE = 6000
    const val NOTIFICATION_ID_SPAN = 1000

    /** How long an unanswered question waits before "If I don't answer" is applied. */
    const val UNANSWERED_DELAY_MS = 24 * 60 * 60_000L

    /** How long "Recording deleted · Undo" stays undoable before the files really go. */
    const val DELETE_UNDO_WINDOW_MS = 10_000L

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L

    /** What the 24-hour check does with a recording. */
    enum class UnansweredAction {
        /** Already answered (or deleted): nothing left to do. */
        NOTHING,
        /** Run the held hand-offs, as if Keep had been tapped. */
        KEEP,
        /** Delete it, with no Undo: nobody is looking at the phone to undo it. */
        DELETE,
    }

    /** Whether a finished recording is held for the question instead of handed off at once. */
    fun shouldAsk(mode: AfterCallMode): Boolean = mode == AfterCallMode.ASK

    /** The stable notification id for [displayName]'s question (and its "deleted · Undo" replacement). */
    fun notificationIdFor(displayName: String): Int =
        NOTIFICATION_ID_BASE + Math.floorMod(displayName.hashCode(), NOTIFICATION_ID_SPAN)

    /**
     * What the 24-hour check does. The setting is read when the check RUNS, not when it was scheduled: a user
     * who switches "If I don't answer" to Keep in the meantime must not lose a recording to the old choice.
     */
    fun unansweredAction(setting: AfterCallUnanswered, stillPending: Boolean): UnansweredAction = when {
        !stillPending -> UnansweredAction.NOTHING
        setting == AfterCallUnanswered.DELETE_AFTER_24H -> UnansweredAction.DELETE
        else -> UnansweredAction.KEEP
    }

    /**
     * Whether [fileName] in the recordings folder belongs to a recording still waiting for its answer — the
     * audio itself or a sidecar beside it (.json details, .md transcript), matched by base name, so a sweep
     * copies none of them to Drive before the user has said keep.
     */
    fun isHeldFile(fileName: String, heldNames: Set<String>): Boolean {
        if (heldNames.isEmpty()) return false
        if (fileName in heldNames) return true
        val base = fileName.substringBeforeLast('.', fileName)
        return heldNames.any { it.substringBeforeLast('.', it) == base }
    }

    /** "m:ss", or "h:mm:ss" from an hour up; null when the length is unknown (≤ 0), so the title omits it. */
    fun formatDuration(durationMs: Long): String? {
        if (durationMs <= 0L) return null
        val total = durationMs / 1_000L
        val hours = total / SECONDS_PER_HOUR
        val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
