/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.baba.callvault.utils.AppLogger

/**
 * Keep / Delete / Undo on the after-call notification. Not exported: only our own notification can send it.
 *
 * The answer is carried in the Intent's DATA, never in an extra: PendingIntent identity ignores extras, so
 * two questions whose intents differed only by an extra would be one PendingIntent, and Keep on the first
 * call's notification would act on the second call's recording.
 */
class AfterCallDecisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val name = intent.data?.takeIf { it.scheme == DATA_SCHEME }?.schemeSpecificPart ?: return
        val app = context.applicationContext
        val pending = goAsync()
        // Off the main thread: every answer commits to the store, and Undo reads the duration.
        Thread {
            try {
                when (action) {
                    ACTION_KEEP -> AfterCallDecision.keep(app, name)
                    ACTION_DELETE -> AfterCallDecision.delete(app, name)
                    ACTION_UNDO -> AfterCallDecision.undo(app, name)
                    ACTION_DISMISSED -> AfterCallDecision.dismissed(app, name)
                    else -> AppLogger.w(TAG, "Unknown after-call action ignored")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "The after-call answer could not be applied", e)
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true; this.name = "cv-after-call" }.start()
    }

    companion object {
        private const val TAG = "CV:AfterCall"
        private const val DATA_SCHEME = "callvault-aftercall"

        const val ACTION_KEEP = "com.baba.callvault.action.AFTER_CALL_KEEP"
        const val ACTION_DELETE = "com.baba.callvault.action.AFTER_CALL_DELETE"
        const val ACTION_UNDO = "com.baba.callvault.action.AFTER_CALL_UNDO"
        const val ACTION_DISMISSED = "com.baba.callvault.action.AFTER_CALL_DISMISSED"

        /** The recording's identity on an Intent: an opaque URI, so the name survives any character in it. */
        fun dataUriFor(displayName: String): Uri = Uri.fromParts(DATA_SCHEME, displayName, null)

        fun pendingIntent(context: Context, action: String, displayName: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, AfterCallDecisionReceiver::class.java)
                    .setAction(action)
                    .setData(dataUriFor(displayName)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
