/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.utils.AppLogger

/**
 * The after-call question in the shade: "Call with Dana recorded · 4:07 — Keep it, or delete it now?", and
 * the "Recording deleted · Undo" that replaces it after Delete.
 *
 * One notification per recording, on the id allocated when it was held, so two calls back to back each
 * keep their own question. Swiping it away is not an answer: the recording stays held and listed, the
 * 24-hour check decides, and startup does not post it again.
 */
internal object AfterCallNotifier {

    private const val TAG = "CV:AfterCall"
    private const val CHANNEL_ID = "after_call_question"

    /** A little longer than the Undo window, so the "deleted" notice outlives the work only if that fails. */
    private const val DELETED_NOTICE_TIMEOUT_MS = AfterCallPolicy.DELETE_UNDO_WINDOW_MS + 5_000L

    /**
     * Whether a question posted now would reach the user: the permission, the app's notifications as a whole,
     * and our own channel (which the user can block on its own). Creates the channel first, so a first-ever
     * question is judged by the channel it will actually be posted on.
     */
    fun canAsk(context: Context): Boolean = runCatching {
        ensureChannel(context)
        val importance = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)?.importance
        AfterCallPolicy.canShowQuestion(
            hasPermission = PermissionChecks.hasNotificationPermission(context),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelImportance = importance,
        )
    }.getOrDefault(false)

    /** Posts (or re-posts) the keep-or-delete question for [held]. Blocking: reads the duration. */
    fun postQuestion(context: Context, held: AfterCallDecisions.Held) {
        val name = held.displayName
        val title = titleFor(context, name, durationOf(context, held))
        val builder = base(context, held)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.after_call_question_text))
            .addAction(0, context.getString(R.string.after_call_keep), receiverIntent(context, AfterCallDecisionReceiver.ACTION_KEEP, held))
            .addAction(0, context.getString(R.string.after_call_delete), receiverIntent(context, AfterCallDecisionReceiver.ACTION_DELETE, held))
            // A swipe is "not now": recorded so startup does not keep putting the question back.
            .setDeleteIntent(receiverIntent(context, AfterCallDecisionReceiver.ACTION_DISMISSED, held))
        notify(context, held.notificationId, builder)
    }

    /** Replaces the question with "Recording deleted" and its Undo, for the length of the Undo window. */
    fun postDeleted(context: Context, held: AfterCallDecisions.Held) {
        val builder = base(context, held)
            .setContentTitle(context.getString(R.string.after_call_deleted_title))
            .setContentText(context.getString(R.string.after_call_deleted_text))
            .setTimeoutAfter(DELETED_NOTICE_TIMEOUT_MS)
            .addAction(0, context.getString(R.string.after_call_undo), receiverIntent(context, AfterCallDecisionReceiver.ACTION_UNDO, held))
        notify(context, held.notificationId, builder)
    }

    fun cancel(context: Context, notificationId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
            .onFailure { AppLogger.w(TAG, "Could not clear the after-call notification: ${it.message}") }
    }

    private fun receiverIntent(context: Context, action: String, held: AfterCallDecisions.Held) =
        AfterCallDecisionReceiver.pendingIntent(context, action, held.displayName, held.notificationId)

    private fun base(context: Context, held: AfterCallDecisions.Held): NotificationCompat.Builder {
        ensureChannel(context)
        // A public version without the name: the lock screen may show that a call is waiting for an answer,
        // never whose call it was — the same privacy line the recording notifications draw.
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.after_call_title_plain))
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openApp(context, held))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
    }

    @SuppressLint("MissingPermission") // Checked just above: hasNotificationPermission.
    private fun notify(context: Context, notificationId: Int, builder: NotificationCompat.Builder) {
        if (!PermissionChecks.hasNotificationPermission(context)) {
            AppLogger.i(TAG, "No notification permission; the after-call question was not shown")
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, builder.build()) }
            .onFailure { AppLogger.w(TAG, "Could not post the after-call notification: ${it.message}") }
    }

    /**
     * Who the call was with, as the file name records it: the contact, else the number, else the app the call
     * was made in. Parsed from the name rather than carried from the call, so carrier and app calls read the
     * same way and a renamed file (anonymous number filled in from the call log) is named correctly.
     */
    private fun titleFor(context: Context, displayName: String, durationMs: Long): String {
        val parsed = runCatching { RecordingsRepository.parseName(displayName) }.getOrNull()
        val party = parsed?.contactName?.takeIf { it.isNotBlank() } ?: parsed?.number?.takeIf { it.isNotBlank() }
        val app = parsed?.voipApp?.takeIf { it.isNotBlank() }
        val head = when {
            party != null -> context.getString(R.string.after_call_title_party, party)
            app != null -> context.getString(R.string.after_call_title_app, app)
            else -> context.getString(R.string.after_call_title_plain)
        }
        val duration = AfterCallPolicy.formatDuration(durationMs) ?: return head
        return "$head · $duration"
    }

    private fun durationOf(context: Context, held: AfterCallDecisions.Held): Long =
        runCatching { AudioDecoder.durationMs(context, held.uri) }.getOrDefault(0L)

    /**
     * Opens CallVault. The per-recording data URI is not decoration: every Intent into MainActivity is
     * otherwise identical to PendingIntent (filterEquals ignores extras), so a request code shared with
     * another notification would make them ONE PendingIntent and FLAG_UPDATE_CURRENT would rewrite the other.
     */
    private fun openApp(context: Context, held: AfterCallDecisions.Held): PendingIntent = PendingIntent.getActivity(
        context,
        held.notificationId,
        Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            data = AfterCallDecisionReceiver.dataUriFor(held.displayName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Idempotent: creating an existing channel changes nothing the user has since changed. HIGH so the
     * question heads up while the phone is still in hand after the call; silent, because the call has just
     * ended and the "recording saved" cue (where enabled) has already made its sound.
     */
    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.after_call_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        runCatching { context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel) }
            .onFailure { AppLogger.w(TAG, "Could not create the after-call channel: ${it.message}") }
    }
}
