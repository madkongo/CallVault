/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import androidx.annotation.VisibleForTesting

/**
 * The one notification CallVault keeps in the shade: "Ready to record calls" while idle, and the call being
 * recorded — with Pause, Mark and Stop — while it is.
 *
 * A recorded carrier call used to show both side by side, and "Ready to record calls" says nothing useful while a
 * call is already being recorded (maintainer, issue #31 follow-up). Neither can simply be hidden: both belong to
 * foreground services, which Android requires to show a notification. What Android does support is two foreground
 * services in one app posting under the **same id** — `ActiveServices.cancelForegroundNotificationLocked` leaves the
 * notification up while another foreground service still holds that id. So the recording service posts under the
 * keep-alive's id, and this object keeps the two from overwriting each other:
 *
 * - while a call is recorded the keep-alive does not post, because "Ready" would replace the only in-call controls;
 * - if the keep-alive has to start meanwhile, it posts the recording's notification rather than its own;
 * - when the recording lets go, the keep-alive is told to put "Ready" back, because Android will not cancel an id
 *   the keep-alive still holds and the finished call's notification would otherwise stay up.
 *
 * Both services run on the main thread of the same process; the fields are volatile for the rare off-thread reader.
 */
object SharedStatusNotice {

    /** The keep-alive's existing id, so a notification already on a user's phone stays the same one. */
    const val ID = 4720

    @Volatile
    private var recordingNotification: Notification? = null

    /** Called when a recorded call releases the notification. The keep-alive re-posts its own content. */
    @Volatile
    var onReleased: (() -> Unit)? = null

    /** True while a recorded call owns the notification. */
    val isHeldByRecording: Boolean get() = recordingNotification != null

    /** Records the notification the recording service has just posted under [ID]; each update replaces the last. */
    fun claim(notification: Notification) {
        recordingNotification = notification
    }

    /** Gives the notification back. Only a real release notifies — the stop path can run more than once. */
    fun release() {
        if (recordingNotification == null) return
        recordingNotification = null
        onReleased?.invoke()
    }

    /**
     * Asks the keep-alive to put its own content back although nothing was claimed. For a recording-service
     * instance that ends without having posted — an orphaned notification action — while a leftover
     * recording notification may still sit on [ID]. Ignored while a call holds the id.
     */
    fun requestRefresh() {
        if (recordingNotification != null) return
        onReleased?.invoke()
    }

    /**
     * Whether the notification actually posted under [ID] is left over from a call: it belongs to another
     * channel than the keep-alive's own, and no recorded call holds the id.
     *
     * It happens because a service's notification is posted asynchronously by system_server, while the
     * keep-alive re-posts "Ready" directly — a recording post can land after "Ready" and stay (LAVA
     * LXX508, 2026-09-26). The keep-alive reads what is posted and heals it.
     */
    fun isStale(postedChannelId: String?, ownChannelId: String): Boolean =
        recordingNotification == null && postedChannelId != null && postedChannelId != ownChannelId

    /** What the keep-alive should post under [ID]: the recorded call's notification if there is one, else [own]. */
    fun contentForKeepAlive(own: Notification): Notification = recordingNotification ?: own

    @VisibleForTesting
    fun resetForTest() {
        recordingNotification = null
        onReleased = null
    }
}
