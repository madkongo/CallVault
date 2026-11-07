/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.app.NotificationManager
import com.baba.callvault.data.AppPreferences.AfterCallAskStyle
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
        /**
         * The recording is gone from the catalog — a merge consumed or renamed it — so only the wait ends.
         * Running hand-offs or a delete against a name that no longer exists would act on nothing, or worse,
         * on whatever reuses the name.
         */
        RELEASE,
        /** Run the held hand-offs, as if Keep had been tapped. */
        KEEP,
        /** Delete it, with no Undo: nobody is looking at the phone to undo it. */
        DELETE,
    }

    /** The facts the 24-hour check decides on, gathered by the worker when it runs. */
    data class HeldFacts(
        val stillPending: Boolean,
        val inCatalog: Boolean,
        val deleteRequested: Boolean,
        /** Starred, flagged, tagged, noted or transcribed: the user did something with this call. */
        val engaged: Boolean,
    )

    /** Whether a finished recording is held for the question instead of handed off at once. */
    fun shouldAsk(mode: AfterCallMode): Boolean = mode == AfterCallMode.ASK

    /**
     * Whether the question can actually reach the user. A hold whose question can never be seen is a recording
     * that silently gets no Drive copy and no transcript for a day, or is deleted unasked — so when this is
     * false the call is treated exactly as in Keep mode.
     *
     * @param channelImportance the question channel's importance, or null when it has not been created yet
     *   (it is then created with ours, which is not NONE).
     */
    fun canShowQuestion(hasPermission: Boolean, notificationsEnabled: Boolean, channelImportance: Int?): Boolean =
        hasPermission && notificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

    /**
     * Whether a fresh call-end question also opens the pop-up. Only for the Pop-up style, only with the
     * overlay permission (it is what lets a background app open a window at all), and never over the lock
     * screen — a locked phone gets the notification, which the lock screen already shows without the name.
     */
    fun shouldPopUp(style: AfterCallAskStyle, canDrawOverlays: Boolean, keyguardLocked: Boolean): Boolean =
        style == AfterCallAskStyle.POPUP && canDrawOverlays && !keyguardLocked

    /**
     * Whether the audio mode says a call is ringing or under way (phone or app call), when the pop-up must stay
     * off the screen. 4 is AudioManager.MODE_CALL_SCREENING (API 30), written as a number to keep this file pure.
     */
    fun isCallAudioMode(mode: Int): Boolean = mode == MODE_RINGTONE || mode == MODE_IN_CALL ||
        mode == MODE_IN_COMMUNICATION || mode == MODE_CALL_SCREENING || mode == MODE_CALL_REDIRECT ||
        mode == MODE_COMMUNICATION_REDIRECT

    private const val MODE_RINGTONE = 1
    private const val MODE_IN_CALL = 2
    private const val MODE_IN_COMMUNICATION = 3
    private const val MODE_CALL_SCREENING = 4
    private const val MODE_CALL_REDIRECT = 5
    private const val MODE_COMMUNICATION_REDIRECT = 6

    /**
     * How long the pop-up ignores Keep and Delete after a card appears (or changes to the next recording): a
     * thumb still on the screen from ending the call must not answer a question it never saw.
     */
    const val TAP_GUARD_MS = 400L

    /** Whether a tap at [nowMs] may answer a card shown at [shownAtMs] (both on the same monotonic clock). */
    fun acceptsTap(shownAtMs: Long, nowMs: Long): Boolean = nowMs - shownAtMs >= TAP_GUARD_MS

    /** Hold only when the user asked to be asked AND the question can be shown. */
    fun shouldHold(mode: AfterCallMode, canShowQuestion: Boolean): Boolean = shouldAsk(mode) && canShowQuestion

    /**
     * The next question's notification id after [last]: monotonic through the band, wrapping at its end.
     * Allocated rather than hashed from the name, so two recordings can never share an id (and replace each
     * other's question) until a thousand questions later. Anything outside the band starts it afresh.
     */
    fun nextNotificationId(last: Int?): Int {
        if (last == null || last !in NOTIFICATION_ID_BASE until NOTIFICATION_ID_BASE + NOTIFICATION_ID_SPAN) {
            return NOTIFICATION_ID_BASE
        }
        return NOTIFICATION_ID_BASE + (last - NOTIFICATION_ID_BASE + 1) % NOTIFICATION_ID_SPAN
    }

    /** How long is left of a question's 24 hours at [now]; zero when overdue, so the check runs at once. */
    fun remainingUnansweredDelayMs(askedAt: Long, now: Long): Long =
        (askedAt + UNANSWERED_DELAY_MS - now).coerceIn(0L, UNANSWERED_DELAY_MS)

    /**
     * Whether startup should post a held recording's question again (it may have been lost to a reboot or a
     * killed process). Not after Delete — the "deleted" notice and its delete work stand — and not after the
     * user swiped it away, which is their way of saying "not now", not a request to be asked on every start.
     */
    fun shouldRepostQuestion(deleteRequested: Boolean, dismissed: Boolean): Boolean = !deleteRequested && !dismissed

    /**
     * What the 24-hour check does. The setting is read when the check RUNS, not when it was scheduled: a user
     * who switches "If I don't answer" to Keep in the meantime must not lose a recording to the old choice.
     *
     * A tapped Delete is always honoured. An unanswered question under "Delete after 24 hours" is still kept
     * when the user engaged with the recording: starring, marking, tagging, noting or transcribing a call is
     * an answer of its own, and deleting it would destroy exactly what they had just added.
     */
    fun unansweredAction(setting: AfterCallUnanswered, facts: HeldFacts): UnansweredAction = when {
        !facts.stillPending -> UnansweredAction.NOTHING
        !facts.inCatalog -> UnansweredAction.RELEASE
        facts.deleteRequested -> UnansweredAction.DELETE
        setting == AfterCallUnanswered.DELETE_AFTER_24H && !facts.engaged -> UnansweredAction.DELETE
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
