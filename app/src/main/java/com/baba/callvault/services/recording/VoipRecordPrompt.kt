/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.baba.callvault.R
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.utils.AppLogger

/**
 * The "an app call is happening — record it?" prompt, shown while **Ask me** is selected for app
 * calls and a call is under way that CallVault has deliberately not started recording.
 *
 * **Why this is a separate notification from the keep-alive one.** The keep-alive notification would
 * be the obvious place — it already reports VoIP recording, and reusing it costs nothing. But its
 * channel is `IMPORTANCE_MIN`, chosen precisely so a permanent status note stays out of the way: no
 * heads-up, no lock screen, collapsed to the bottom of the shade. A prompt nobody sees is not a
 * prompt. This one goes on the same high-importance channel as the carrier standby prompt, which
 * exists for exactly the same purpose and has to be equally visible.
 *
 * It is transient by construction: shown when the call starts, cancelled the moment recording begins
 * or the call ends. [RecordingNotificationHelper.createNotificationChannels] owns the channel.
 */
object VoipRecordPrompt {

    private const val TAG = "CV:VoipPrompt"

    /** Distinct from the recording service (1) and error (2) notifications. */
    internal const val NOTIFICATION_ID = 3

    private const val REQUEST_CODE = 3001

    /**
     * Posts the prompt for a call from [appLabel] (the calling app's name, when it could be resolved).
     *
     * @param appLabel Shown so the user knows which app is calling; the text falls back to a generic
     *                 wording when the app could not be identified, which happens when the calling app
     *                 is not allowed to post notifications.
     */
    fun show(base: Context, appLabel: String?) {
        val context = LocalizedContext.of(base)
        val intent = Intent(context, DaemonKeepAliveService::class.java).apply {
            action = DaemonKeepAliveService.ACTION_VOIP_RECORD_NOW
        }
        val pending = PendingIntent.getService(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text =
            if (appLabel.isNullOrBlank()) context.getString(R.string.voip_prompt_text)
            else context.getString(R.string.voip_prompt_text_named, appLabel)

        // Without its channel (phone-call recording never ran) this prompt was silently dropped (review N22).
        RecordingNotificationHelper.ensureChannels(context)
        val notification = NotificationCompat.Builder(context, RecordingNotificationHelper.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.voip_prompt_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Dismissable: declining is a legitimate answer to "record this?", and re-posting a
            // prompt the user swiped away would be nagging of the kind this mode exists to avoid.
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_mic, context.getString(R.string.general_record), pending)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }.onFailure { AppLogger.w(TAG, "Could not post the app-call prompt: ${it.message}") }
    }

    /** Removes the prompt — because recording started, or because the call ended unrecorded. */
    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }.onFailure { AppLogger.w(TAG, "Could not cancel the app-call prompt: ${it.message}") }
    }
}
