/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.app.NotificationManager
import android.content.Context
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.recording.RecordingNotificationHelper

/**
 * The "Notification channels" block of the debug report (review N22 follow-up).
 *
 * A missing recording channel crashed every app call on a vivo (Android 17), and a blocked one silences the in-call
 * controls; nothing in a report said which channels existed. Read when the report is built, so it is there with debug
 * logging off. Channel ids, importance and group only: nothing personal.
 */
object NotificationChannelsReport {

    data class Channel(val id: String, val importance: Int, val group: String?)

    /** The channels a recorded call depends on; one that does not exist is named as missing. */
    private val EXPECTED = listOf(
        DaemonKeepAliveService.CHANNEL_ID,
        RecordingNotificationHelper.CHANNEL_ID_SERVICE,
        RecordingNotificationHelper.CHANNEL_ID_ERROR,
    )

    /** Pure formatting. [appNotificationsEnabled] is null when it could not be read. */
    fun lines(appNotificationsEnabled: Boolean?, channels: List<Channel>, expected: List<String>): List<String> {
        val enabled = when (appNotificationsEnabled) {
            true -> "yes"
            false -> "no"
            null -> "?"
        }
        val present = channels.sortedBy { it.id }.map {
            val blocked = if (it.importance == NotificationManager.IMPORTANCE_NONE) "yes" else "no"
            "${it.id}: importance ${importanceName(it.importance)}, blocked $blocked, group ${it.group ?: "-"}"
        }
        val known = channels.map { it.id }.toSet()
        val missing = expected.filterNot { it in known }.map { "$it: MISSING" }
        return listOf("App notifications enabled: $enabled") + (present + missing).ifEmpty { listOf("no channels") }
    }

    /** Reads this app's channels from the phone; never throws. */
    fun reportLines(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return listOf("unavailable: no NotificationManager")
        val enabled = runCatching { manager.areNotificationsEnabled() }.getOrNull()
        val channels = manager.notificationChannels.map { Channel(it.id, it.importance, it.group) }
        lines(enabled, channels, EXPECTED)
    }.getOrElse { listOf("unavailable: ${it.javaClass.simpleName}") }

    private fun importanceName(importance: Int): String = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> "NONE"
        NotificationManager.IMPORTANCE_MIN -> "MIN"
        NotificationManager.IMPORTANCE_LOW -> "LOW"
        NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
        NotificationManager.IMPORTANCE_HIGH -> "HIGH"
        NotificationManager.IMPORTANCE_MAX -> "MAX"
        NotificationManager.IMPORTANCE_UNSPECIFIED -> "UNSPECIFIED"
        else -> importance.toString()
    }
}
