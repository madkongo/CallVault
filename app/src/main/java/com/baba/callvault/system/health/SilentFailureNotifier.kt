/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger

/**
 * Tells the user when CallVault has quietly stopped doing its job.
 *
 * Two failures share this, because from where the user stands they are one thing — "the app is not
 * working and nobody said so":
 *
 * - **The recorder could not be brought up after a reboot.** Almost always the off-Wi-Fi case: the
 *   ADB listener does not survive a reboot and cannot be re-armed until the phone associates with a
 *   Wi-Fi network once. Until this, the app tried, failed, wrote a line to the log, and let the user
 *   find out by missing a call.
 * - **Recordings have stopped reaching Drive.** The failure that costs the most and shows the least:
 *   sync dies, recording carries on, everything looks fine, and it surfaces when the phone is
 *   replaced and the cloud copy nobody checked turns out not to exist.
 *
 * Both notifications are **self-clearing**. Posting a warning that outlives its cause is its own bug:
 * it teaches the user that CallVault's warnings are noise, and the next real one gets swiped away
 * with the rest.
 *
 * A channel of its own, at DEFAULT importance, so it is separable from the recording notifications —
 * a user who mutes the "recording started" chatter must not thereby mute "nothing is being recorded".
 */
object SilentFailureNotifier {

    private const val TAG = "CV:SilentFailure"
    private const val CHANNEL_ID = "health_warnings"

    /** Distinct IDs: the two conditions are independent and either may be true alone. */
    private const val ID_RECORDER_UNAVAILABLE = 4716
    private const val ID_SYNC_STALLED = 4717
    private const val ID_SHIZUKU_STOPPED = 4718

    /**
     * The recorder could not be started. Posted from the boot path, where a failure is invisible.
     *
     * The wording says **associate with a Wi-Fi network**, not "connect to the internet". They are
     * not the same, and the difference is the whole recovery: the gate is a Wi-Fi association, so any
     * access point will do — a café, a neighbour's locked router the phone still remembers, a second
     * phone's hotspot the user is not even using for data. Telling people they "need internet" makes
     * a ten-second fix sound like a trip home.
     */
    fun warnRecorderUnavailable(context: Context) {
        // The advice has to match the mode, the same rule the empty-file warning already follows.
        // Telling a Shizuku user to join a Wi-Fi network sends them to a setting they have never
        // touched and do not need, and away from the one thing that is actually actionable: whether
        // Shizuku is running. A confidently wrong instruction is worse than no notification.
        val text =
            if (AppPreferences(context).getPrivilegedMode().needsShizuku) {
                R.string.notif_health_recorder_text_shizuku
            } else {
                R.string.notif_health_recorder_text
            }
        post(
            context,
            id = ID_RECORDER_UNAVAILABLE,
            title = context.getString(R.string.notif_health_recorder_title),
            text = context.getString(text)
        )
    }

    /** The recorder came up. Clears any standing warning. */
    fun clearRecorderUnavailable(context: Context) = clear(context, ID_RECORDER_UNAVAILABLE)

    /**
     * Says that CallVault restarted Android's debugging service and stopped the user's Shizuku with it.
     *
     * Shizuku belongs to its own users — app managers, ad-blockers, file managers — and #39's reporter
     * could only describe this as Shizuku "disabling automatically within a second". The restart itself
     * is unavoidable (see [com.baba.callvault.integrations.adb.ShizukuChurnPolicy]); being silent about
     * it is not. Posted with its own id so it never replaces the "cannot record" warning, which is about
     * CallVault and is the more urgent of the two.
     */
    fun warnShizukuStopped(context: Context) = post(
        context,
        id = ID_SHIZUKU_STOPPED,
        title = context.getString(R.string.notif_health_shizuku_stopped_title),
        text = context.getString(R.string.notif_health_shizuku_stopped_text),
    )

    /**
     * Says that Shizuku was stopped by the restart and **has been started again**.
     *
     * Good news rather than a warning, but it shares [ID_SHIZUKU_STOPPED] on purpose: the three Shizuku
     * outcomes are one story and only ever one of them is true at a time, so posting them under one id
     * means the later answer replaces the earlier one instead of stacking two contradictory lines in the
     * shade. Nothing here may take the recorder's own id — see
     * [com.baba.callvault.integrations.adb.ShizukuHealPolicy].
     */
    fun noteShizukuRestarted(context: Context) = post(
        context,
        id = ID_SHIZUKU_STOPPED,
        title = context.getString(R.string.notif_health_shizuku_restarted_title),
        text = context.getString(R.string.notif_health_shizuku_restarted_text),
    )

    /**
     * Says that CallVault stopped Shizuku, tried to start it again, and could not.
     *
     * The honest half of the heal. Posting "Shizuku is back" on the strength of having run its starter
     * would be the Drive-health false positive again — a claim about the present with no evidence about
     * the present — so an unverified start says so and names the one thing the user can do.
     */
    fun warnShizukuRestartFailed(context: Context) = post(
        context,
        id = ID_SHIZUKU_STOPPED,
        title = context.getString(R.string.notif_health_shizuku_restart_failed_title),
        text = context.getString(R.string.notif_health_shizuku_restart_failed_text),
    )

    /**
     * Checks whether recordings are reaching Drive, and posts or clears accordingly.
     *
     * Reads the catalog rather than asking the provider: a row with a device copy and no Drive copy
     * is the symptom regardless of the cause, so this notices a sync that stopped being *attempted*
     * — which a per-upload failure handler cannot, because no upload ever runs to fail.
     *
     * Never throws. A health check that can break the caller is worse than no health check.
     */
    suspend fun checkSyncHealth(context: Context) {
        runCatching {
            val prefs = AppPreferences(context)
            // Nothing to sync, so nothing can be stalled. Also clears a warning left over from
            // before the user switched to device-only storage.
            if (prefs.getStorageTarget() == StorageTarget.LOCAL) {
                clear(context, ID_SYNC_STALLED)
                return@runCatching
            }

            val catalogued = RecordingCatalog.all(context)
            val unsynced = catalogued
                // Asked of the policy, which is where the rule can be proved without a device. An
                // import is excluded there, and that exclusion is what stops this notification
                // crying wolf — see SyncHealthPolicy.countsAsUnsynced.
                .filter {
                    SyncHealthPolicy.countsAsUnsynced(
                        displayName = it.displayName,
                        hasLocalCopy = it.localUri != null,
                        hasDriveCopy = it.driveUri != null,
                    )
                }
                .map { it.lastModified }
            // The most recent recording known to be in Drive. This is what turns "some old file has
            // no Drive copy" into "copying has stopped" — or, far more often, rules it out.
            val newestSynced = catalogued
                .filter { it.driveUri != null }
                .maxOfOrNull { it.lastModified } ?: 0L

            val stalled = SyncHealthPolicy.countStalled(
                unsynced, newestSynced, prefs.getSyncScheduleMode(), System.currentTimeMillis()
            )
            if (stalled <= 0) {
                clear(context, ID_SYNC_STALLED)
                return@runCatching
            }

            AppLogger.w(TAG, "$stalled recording(s) have not reached Drive; warning the user.")
            post(
                context,
                id = ID_SYNC_STALLED,
                title = context.getString(R.string.notif_health_sync_title),
                // No count in the text, deliberately. It would need per-locale plural rules to
                // read properly, and it invites the wrong question — "is three bad?" — when the only
                // thing that matters is that copying has stopped. The number is in the app.
                text = context.getString(R.string.notif_health_sync_text)
            )
        }.onFailure { AppLogger.w(TAG, "Sync health check failed: ${it.message}") }
    }

    // POST_NOTIFICATIONS is checked explicitly rather than relying on notify()'s silent no-op, which
    // is also what satisfies the MissingPermission lint check.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, title: String, text: String) {
        createChannel(context)
        val open = PendingIntent.getActivity(
            context,
            // The destination's own request code, not the notification id. Both warnings here open
            // the same place, and the id would collide with another notification's PendingIntent —
            // see NotificationDestination.requestCode for why that matters.
            NotificationDestination.Status.requestCode,
            // Component AND package, though the component alone already makes this explicit: the
            // pair is what a static analyser can see without following the constructor, and this
            // PendingIntent leaves the app inside a notification, where an implicit one would be a
            // real hole. It is immutable for the same reason.
            Intent(context, MainActivity::class.java).apply {
                setPackage(context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Says what the tap was about. Both of these warnings are only actionable from the
                // status card, which is the one thing Home has always been.
                putExtra(NotificationDestination.EXTRA, NotificationDestination.Status.key)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            // The text is a sentence or two and matters more than most; collapsed to one line it
            // would lose the part that says what to actually do about it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (!PermissionChecks.hasNotificationPermission(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { AppLogger.w(TAG, "Failed to post health notification: ${it.message}") }
    }

    private fun clear(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_health_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { setShowBadge(true) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
