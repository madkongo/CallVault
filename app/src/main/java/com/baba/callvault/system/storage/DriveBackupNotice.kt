/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.R
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger

/**
 * "Drive backup isn't working": the user-visible end of a sweep that could not list the Drive folder.
 *
 * Since review C11 nothing copies from the app's own process, so a phone whose `:drive` copier cannot list
 * backs nothing up through the sweep. That must not be silent while the user believes Drive holds their calls.
 * Posted once, on the attempt that gives up ([isGivingUp]), in the "Problems that need you" channel
 * ([SilentFailureNotifier]); not again on later failed runs; cleared (and allowed again) as soon as Drive is
 * reached: a listing in the sweep or a copy after a call. Every post and clear is journalled.
 */
object DriveBackupNotice {

    private const val TAG = "CV:DriveBackupNotice"
    private const val PREFS = "drive_backup_notice"
    private const val KEY_POSTED = "posted"

    /** What the user is told went wrong, in a few words. */
    enum class Reason(val text: Int) {
        /** The copier died, timed out or never connected. */
        NO_ANSWER(R.string.notif_drive_backup_reason_no_answer),

        /** The copier answered, but could not read the folder (permission, folder gone, still loading...). */
        FOLDER_UNREADABLE(R.string.notif_drive_backup_reason_folder),

        /** The sweep itself threw; the text names the error (the `detail` of [onGaveUp]). */
        ERROR(R.string.notif_drive_backup_reason_error),
    }

    enum class Event { GAVE_UP, REACHED }
    enum class Action { POST, CLEAR, NOTHING }

    /** Post once per failure spell; clear only what was posted. */
    fun decide(isPosted: Boolean, event: Event): Action = when (event) {
        Event.GAVE_UP -> if (isPosted) Action.NOTHING else Action.POST
        Event.REACHED -> if (isPosted) Action.CLEAR else Action.NOTHING
    }

    /** True when attempt [runAttemptCount] (0-based) is the one after which the sweep stops retrying. */
    fun isGivingUp(runAttemptCount: Int): Boolean = !CloudCopyPolicy.shouldRetryListFailure(runAttemptCount)

    /**
     * The sweep listed the folder, but on its last attempt not one copy went through while some failed: nothing
     * else uploads (review C11), so this is giving up too (review N6).
     */
    fun isCopyGivingUp(copied: Int, failures: Int, runAttemptCount: Int): Boolean =
        copied == 0 && failures > 0 && CloudCopyPolicy.isLastAttempt(runAttemptCount)

    /**
     * The sweep spent its last attempt without listing the Drive folder, or crashed on it; [why] is the journal's
     * reason, [detail] fills [Reason.ERROR]'s text (the error's name).
     */
    fun onGaveUp(context: Context, reason: Reason, why: String, detail: String? = null) {
        val prefs = prefs(context)
        when (decide(prefs.getBoolean(KEY_POSTED, false), Event.GAVE_UP)) {
            Action.POST -> {
                val localized = LocalizedContext.of(context)
                val text = if (detail != null) localized.getString(reason.text, detail) else localized.getString(reason.text)
                if (!SilentFailureNotifier.warnDriveBackupBroken(context, text)) {
                    // Not remembered as posted: the next give-up tries again.
                    AppLogger.w(TAG, "Drive backup notice could NOT be shown ($reason): $why")
                    DriveCopyJournal.record(
                        context,
                        "user notice NOT shown (no notification permission, or the system refused it); tried again at the next give-up. Cause: $why",
                    )
                    return
                }
                prefs.edit().putBoolean(KEY_POSTED, true).apply()
                AppLogger.w(TAG, "Drive backup notice POSTED ($reason): $why")
                DriveCopyJournal.record(context, "user notice POSTED: Drive backup isn't working ($reason); cause: $why")
            }
            else -> DriveCopyJournal.record(context, "user notice already posted for this failure spell; not posted again ($reason)")
        }
    }

    /** Drive was reached ([how]: a listing or a copy). Clears a posted notice. */
    fun onDriveReached(context: Context, how: String) {
        val prefs = prefs(context)
        if (decide(prefs.getBoolean(KEY_POSTED, false), Event.REACHED) != Action.CLEAR) return
        SilentFailureNotifier.clearDriveBackupBroken(context)
        prefs.edit().putBoolean(KEY_POSTED, false).apply()
        AppLogger.i(TAG, "Drive backup notice CLEARED: $how")
        DriveCopyJournal.record(context, "user notice CLEARED: Drive reached again ($how)")
    }

    /** Drive sync was switched off (device-only / immediate): a posted notice is about a backup that no longer runs. */
    fun onDriveSyncOff(context: Context, why: String) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_POSTED, false)) return
        SilentFailureNotifier.clearDriveBackupBroken(context)
        prefs.edit().putBoolean(KEY_POSTED, false).apply()
        AppLogger.i(TAG, "Drive backup notice CLEARED: Drive sync turned off ($why)")
        DriveCopyJournal.record(context, "user notice CLEARED: Drive sync turned off ($why)")
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
