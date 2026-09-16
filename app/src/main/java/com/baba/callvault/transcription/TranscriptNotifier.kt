/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.ui.common.RecordingLabel
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.ui.navigation.OpenTranscriptRequest
import com.baba.callvault.utils.AppLogger

/**
 * Says, in the shade, that a transcription has ended.
 *
 * **Why it exists.** Until now a transcription finished in perfect silence. For a call that is only
 * mildly unhelpful — the row gains an icon and the user finds it eventually. For a voice note arriving
 * through the share sheet it is the whole feature failing: the user shared a file, was told it was
 * queued, and left CallVault. Nothing was ever going to tell them it was ready. And for a
 * **"Transcribe only"** import, whose audio is deleted the moment the words are stored, the
 * notification is not a convenience at all — it is the delivery, and the only place the app ever says
 * what happened to the file.
 *
 * **A failure gets a word too**, because it was equally silent: today a failed run leaves a red icon
 * on a page nobody has been given a reason to open.
 *
 * ## The two rules that keep it quiet
 *
 * - **One notification per outcome, merged, never one per recording.** See [TranscriptNotice]: the
 *   nightly sweep may finish fifty recordings, and fifty lines in the shade would be worse than the
 *   silence this replaces.
 * - **Its own channel at [NotificationManager.IMPORTANCE_LOW]** — in the shade, never a sound, never a
 *   heads-up. Separable from every other channel, so someone who does not want this can turn off this
 *   and nothing else; and quiet by construction, because the mode that transcribes after every call
 *   would otherwise chime after every call.
 *
 * It touches no id the recorder uses. `SharedStatusNotice.ID` (4720) is the one notification CallVault
 * keeps in the shade for recording, held jointly by two foreground services; posting anything under
 * it here would replace the in-call controls. These ids sit above it, and above the health and update
 * notifications, which already collide with each other at 4716/4717.
 */
object TranscriptNotifier {

    private const val TAG = "CV:TranscriptNotice"

    /** Its own channel, so it can be silenced without silencing anything about recording. */
    private const val CHANNEL_ID = "transcripts"

    /**
     * Two ids, not one.
     *
     * A finished transcript and a failed run are different news and a user may have both waiting. One
     * id would mean the last thing to happen erased the other — most likely a success quietly erasing
     * the failure, which is the half that needs acting on.
     */
    private const val ID_READY = 4721
    private const val ID_FAILED = 4722

    /**
     * Where the merge state is kept: on the notification itself.
     *
     * Nothing is stored anywhere else, deliberately. The shade is the state — if the user swiped it
     * away they have seen it, and the next transcript starts a fresh count and gets named again.
     */
    private const val EXTRA_COUNT = "com.baba.callvault.notice.COUNT"
    private const val EXTRA_NAME = "com.baba.callvault.notice.NAME"
    private const val EXTRA_AUDIO_DELETED = "com.baba.callvault.notice.AUDIO_DELETED"

    /**
     * Says that [displayName] ended as [outcome].
     *
     * Suspending because the name to show has to be looked up: the row the user knows says who the
     * call was with, and a notification quoting
     * `20260916_101010.123+0300_in_+972500000000.ogg` at them instead would be the app speaking its
     * own bookkeeping out loud. The lookup falls back to the file's own label, which is what an
     * import and an orphan have.
     */
    suspend fun announce(context: Context, displayName: String, outcome: TranscriptNotice.Outcome) {
        // Resolved before anything is posted, and best-effort: a folder that cannot be listed is not
        // a reason to swallow the one message telling the user their transcript is ready.
        val label = runCatching {
            RecordingLabel.forDisplayName(RecordingsRepository.listRecordings(context), displayName)
        }.getOrElse { RecordingLabel.forName(displayName) }

        when (outcome) {
            TranscriptNotice.Outcome.Stored -> stored(context, displayName, label, false)
            TranscriptNotice.Outcome.StoredAndAudioDeleted -> stored(context, displayName, label, true)
            TranscriptNotice.Outcome.Failed -> failed(context, displayName, label)
        }
    }

    /**
     * A transcript has been stored.
     *
     * @param audioDeleted true when this was a "Transcribe only" import and the audio went with the
     *   transcript being written. It changes the sentence, because that is the one case where the
     *   user is owed an account of a file that is no longer on their phone.
     */
    private fun stored(context: Context, displayName: String, label: String, audioDeleted: Boolean) {
        val shade = TranscriptNotice.after(read(context, ID_READY), displayName, audioDeleted)
        val one = shade.displayName

        post(
            context = context,
            id = ID_READY,
            destination = NotificationDestination.Transcript,
            // Only when it names one. A tap that opened the last of ten would take the user to a
            // transcript they never asked about, past the nine the notification was also about.
            openTranscript = one,
            shade = shade,
            title = if (one == null) {
                context.getString(R.string.transcript_notice_ready_many_title, shade.count)
            } else {
                context.getString(R.string.transcript_notice_ready_title)
            },
            text = when {
                one == null -> context.getString(R.string.transcript_notice_ready_many_text)
                shade.audioDeleted ->
                    context.getString(R.string.transcript_notice_ready_deleted_text, label)
                else -> label
            },
        )
    }

    /** A transcription ended in failure. The page it opens is the one with the retry on it. */
    private fun failed(context: Context, displayName: String, label: String) {
        val shade = TranscriptNotice.after(read(context, ID_FAILED), displayName)
        val one = shade.displayName

        post(
            context = context,
            id = ID_FAILED,
            destination = NotificationDestination.TranscriptFailed,
            // Never a name, even for one: the reading view has nothing to show for a failed run. The
            // page does — the "didn't finish" group, where the whole card retries.
            openTranscript = null,
            shade = shade,
            title = if (one == null) {
                context.getString(R.string.transcript_notice_failed_many_title, shade.count)
            } else {
                context.getString(R.string.transcript_notice_failed_title)
            },
            text = if (one == null) {
                context.getString(R.string.transcript_notice_failed_many_text)
            } else {
                context.getString(R.string.transcript_notice_failed_text, label)
            },
        )
    }

    /**
     * What our own notification with [id] is currently saying, or null when none is showing.
     *
     * `getActiveNotifications` returns this app's notifications and no other's, so nothing here reads
     * anything private. Wrapped because it is a system call over a binder: on a phone where it throws,
     * the worst that happens is the count restarts at one.
     */
    private fun read(context: Context, id: Int): TranscriptNotice.Shade? = runCatching {
        val live = context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.id == id }
            ?: return@runCatching null
        val extras: Bundle = live.notification.extras
        val count = extras.getInt(EXTRA_COUNT, 0)
        if (count <= 0) return@runCatching null
        TranscriptNotice.Shade(
            count = count,
            displayName = extras.getString(EXTRA_NAME),
            audioDeleted = extras.getBoolean(EXTRA_AUDIO_DELETED, false),
        )
    }.getOrNull()

    // POST_NOTIFICATIONS is checked explicitly rather than relying on notify()'s silent no-op, which
    // is also what satisfies the MissingPermission lint check.
    @SuppressLint("MissingPermission")
    private fun post(
        context: Context,
        id: Int,
        destination: NotificationDestination,
        openTranscript: String?,
        shade: TranscriptNotice.Shade,
        title: String,
        text: String,
    ) {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            // The destination's own request code, never the notification id: every Intent into
            // MainActivity looks identical to PendingIntent (filterEquals ignores extras), so two
            // sharing a code are ONE object and FLAG_UPDATE_CURRENT hands both the last one's
            // destination. See NotificationDestination.requestCode.
            destination.requestCode,
            // Component AND package, and immutable: this PendingIntent leaves the app inside a
            // notification, where an implicit Intent would be a real hole.
            Intent(context, MainActivity::class.java).apply {
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationDestination.EXTRA, destination.key)
                openTranscript?.let { putExtra(OpenTranscriptRequest.EXTRA, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            // The deleted-audio sentence is the longest thing here and the only one that has to be
            // read in full: it is the app's one account of a file that is no longer on the phone.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            // A merge re-posts under the same id on every finish. Without this, a batch would alert
            // once per recording — which is the noise the merge exists to avoid, arriving by the
            // other door.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // It names a private call. Hidden on the lock screen for the same reason the recording
            // notifications are.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addExtras(
                Bundle().apply {
                    putInt(EXTRA_COUNT, shade.count)
                    putString(EXTRA_NAME, shade.displayName)
                    putBoolean(EXTRA_AUDIO_DELETED, shade.audioDeleted)
                }
            )
            .build()

        if (!PermissionChecks.hasNotificationPermission(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { AppLogger.w(TAG, "Could not post the transcript notice: ${it.message}") }
    }

    /** Idempotent: creating an existing channel updates nothing the user has since changed. */
    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.transcript_notice_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
