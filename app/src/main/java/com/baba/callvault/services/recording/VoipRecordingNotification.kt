/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.R
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger

/**
 * The ongoing notification shown while an app call is being recorded.
 *
 * Until this existed, a VoIP recording in progress had **no controls at all**: the carrier path's
 * notification is driven by `RecordingForegroundService`, which this path deliberately does not go
 * through, so an app call could be started and finished with nothing to stop it and nothing to mark
 * a moment in it. For anyone whose calls are mostly in a messenger, that was the whole feature
 * missing.
 *
 * Pause/Resume, Mark and Stop — the same three the carrier notification offers. Pause reaches the
 * daemon over [com.baba.callvault.server.IRecorderService.setVoipPaused]; an older daemon left over
 * from a previous APK does not implement it, and the caller degrades rather than showing a button
 * that does nothing.
 */
object VoipRecordingNotification {

    private const val TAG = "CV:VoipRecNotification"

    /** Distinct from the recording service (1), error (2) and record-prompt (3) notifications. */
    internal const val LEGACY_NOTIFICATION_ID = 5

    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        appLabel: String?,
        marks: Int = 0,
        paused: Boolean = false,
        heldForCall: Boolean = false,
    ) {
        // Its channel is created by the phone-call service; a phone that only records app calls may not have it yet.
        RecordingNotificationHelper.ensureChannels(context)
        val notification = build(context, appLabel, marks, paused, heldForCall)
        if (!PermissionChecks.hasNotificationPermission(context)) return
        // Posted under the keep-alive's id and claimed, exactly like a carrier call (issue #31): one
        // notification during the call, with Pause, Mark and Stop, instead of this one beside "Ready"
        // reworded as "Recording VoIP call" (BabApps item 123). The keep-alive stays a foreground service
        // holding that id; it is never stopped or hidden. dismiss() gives the id back and "Ready" returns.
        runCatching {
            // Claimed first, so the keep-alive never reads the id as free and puts "Ready" over it.
            SharedStatusNotice.claim(SharedStatusNotice.Owner.VOIP, notification)
            // Through the keep-alive's startForeground (review C23, Q49): on Android 17 Pixels notify() on this
            // foreground id does not replace what is shown, so Pause, Mark and Stop never appeared (#73).
            val posted = SharedStatusNotice.foregroundPoster?.invoke(notification) == true
            if (!posted) {
                AppLogger.w(TAG, "App-call notice: the keep-alive could not post it as its foreground notice; posting with notify()")
                NotificationManagerCompat.from(context).notify(SharedStatusNotice.ID, notification)
            }
            // Left over from a build that still posted under id 5.
            NotificationManagerCompat.from(context).cancel(LEGACY_NOTIFICATION_ID)
        }.onFailure { AppLogger.w(TAG, "Could not post the VoIP recording notification: ${it.message}") }
    }

    /** The notification itself, with its text from the app's language ([LocalizedContext]). */
    internal fun build(
        base: Context,
        appLabel: String?,
        marks: Int,
        paused: Boolean,
        heldForCall: Boolean,
    ): android.app.Notification {
        val context = LocalizedContext.of(base)
        val title =
            if (appLabel.isNullOrBlank()) context.getString(R.string.voip_recording_title)
            else context.getString(R.string.voip_recording_title_named, appLabel)

        val builder = NotificationCompat.Builder(context, RecordingNotificationHelper.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(
                context.getString(
                    when {
                        // Said explicitly, because from the outside this is indistinguishable from
                        // the recording having stopped — and the whole point is that it has not.
                        heldForCall -> R.string.voip_recording_held
                        paused -> R.string.voip_recording_paused
                        else -> R.string.voip_recording_text
                    }
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // No pause button while held for a phone call: the capture is already released, and a button
        // offering to pause something that is not running is worse than no button.
        if (!heldForCall) builder.addAction(
            R.drawable.ic_mic,
            context.getString(if (paused) R.string.general_resume else R.string.general_pause),
            serviceIntent(
                context,
                if (paused) DaemonKeepAliveService.ACTION_VOIP_RESUME
                else DaemonKeepAliveService.ACTION_VOIP_PAUSE
            )
        )
        // The count is on the button, not in a toast: this action is pressed with the shade open,
        // and a toast renders behind the shade where it can never be seen.
        builder.addAction(
            R.drawable.ic_mic,
            if (marks > 0) context.getString(R.string.general_flag_count, marks)
            else context.getString(R.string.general_flag),
            serviceIntent(context, DaemonKeepAliveService.ACTION_VOIP_FLAG_MOMENT)
        )
        // Last, like the carrier notification, and for the same reason: it is the one button here
        // that cannot be undone.
        builder.addAction(
            R.drawable.ic_stop,
            context.getString(R.string.general_stop),
            serviceIntent(context, DaemonKeepAliveService.ACTION_VOIP_STOP)
        )

        return builder.build()
    }

    fun dismiss(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(LEGACY_NOTIFICATION_ID) }
        // Hands the shared id back: the keep-alive puts "Ready to record calls" in its place.
        SharedStatusNotice.release(SharedStatusNotice.Owner.VOIP)
    }

    /**
     * A PendingIntent per action, keyed by the action string.
     *
     * Same trap the carrier notification had: under FLAG_UPDATE_CURRENT two actions sharing a
     * request code are one PendingIntent, and Mark would end the recording.
     */
    private fun serviceIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            // setPackage alongside the component: setting an action on an otherwise explicit
            // Intent is what makes it read as implicit to a static analyser, and these buttons
            // stop and mark a live recording — not something to leave resolvable by anyone else.
            Intent(context, DaemonKeepAliveService::class.java).apply {
                setPackage(context.packageName)
                this.action = action
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
