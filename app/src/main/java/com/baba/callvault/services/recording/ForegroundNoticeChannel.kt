/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.baba.callvault.utils.AppLogger

/**
 * Whether a notification may be handed to `startForeground` (review N22).
 *
 * Android answers a foreground notice whose channel does not exist with
 * `CannotPostForegroundServiceNotificationException` ("Bad notification for startForeground"), delivered later on the
 * main thread, so it cannot be caught and it kills the app. `notify()` only drops such a notice. On a vivo V2546A
 * (Android 17, 2026-11-20) every app call crashed this way: the user had never recorded a phone call, so the recording
 * channel, created only by the phone-call service, did not exist when the app-call notice went through the keep-alive's
 * `startForeground` (review C23).
 */
object ForegroundNoticeChannel {

    private const val TAG = "CV:FgsChannel"

    enum class Verdict { OK, MISSING_CHANNEL }

    /** Pure decision: a notice may go to startForeground only when its channel provably exists. */
    fun verdict(channelId: String?, exists: (String) -> Boolean): Verdict {
        if (channelId.isNullOrBlank()) return Verdict.MISSING_CHANNEL
        val present = runCatching { exists(channelId) }.getOrDefault(false)
        return if (present) Verdict.OK else Verdict.MISSING_CHANNEL
    }

    /**
     * True when [notification] may be posted through `startForeground`. If its channel is missing it creates the
     * recording channels again (bypassing the once-per-process cache: the channel may have been deleted since), then
     * decides; a false answer is logged with the channel id.
     */
    fun usable(context: Context, notification: Notification): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val id = notification.channelId
        val exists: (String) -> Boolean = { manager.getNotificationChannel(it) != null }
        if (verdict(id, exists) == Verdict.OK) return true
        RecordingNotificationHelper.ensureChannels(context, force = true)
        if (verdict(id, exists) == Verdict.OK) {
            AppLogger.w(TAG, "Notification channel '$id' was missing; created it before the foreground post")
            return true
        }
        AppLogger.e(TAG, "Notification channel '$id' does not exist; not posting it as a foreground notice (it would crash the app)")
        return false
    }
}
