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
    private const val KEY_POSTED_FOR_COPIES = "posted_for_copies"
    private const val KEY_FAILED_RUNS = "failed_copy_runs"
    private const val KEY_SPELL_START = "failed_copy_spell_start"

    /** What the user is told went wrong, in a few words. */
    enum class Reason(val text: Int) {
        /** The copier died, timed out or never connected. */
        NO_ANSWER(R.string.notif_drive_backup_reason_no_answer),

        /** The copier answered, but could not read the folder (permission, folder gone, still loading...). */
        FOLDER_UNREADABLE(R.string.notif_drive_backup_reason_folder),

        /** The sweep itself threw; the text names the error (the `detail` of [onGaveUp]). */
        ERROR(R.string.notif_drive_backup_reason_error),

        /** The folder lists, but every copy of the whole failure spell failed (review N6). */
        COPIES_FAILING(R.string.notif_drive_backup_reason_copies),
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

    /** Test seam: the clock the spell's length is measured with. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** Runs in a row (across retries and days) in which copies were tried and nothing newer than the failures reached Drive. */
    fun nextFailedRuns(previous: Int, driveProved: Boolean, failures: Int): Int =
        if (driveProved || failures <= 0) 0 else previous + 1

    /**
     * The failure spell is long enough to tell the user (review N6): as many fruitless runs as the sweep has attempts
     * AND at least [MIN_SPELL_MS] since the first, so one retry chain of a single stuck file (all inside ~90 minutes)
     * never alarms. Immediate mode does not feed the spell (the sweep is not scheduled there); the 2-day symptom check
     * covers it.
     */
    fun isCopySpellGivingUp(failedRuns: Int, spellMs: Long): Boolean =
        failedRuns >= CloudCopyPolicy.MAX_ATTEMPTS && spellMs >= MIN_SPELL_MS

    const val MIN_SPELL_MS = 24L * 60 * 60 * 1000

    /**
     * A finished sweep run that listed the folder (review N6). [driveProved] is true only when a copy went through in
     * this run, or Drive holds a recording NEWER than the newest one that failed (an old file Drive already has proves
     * nothing about new uploads). Only a spell with no proof, for [CloudCopyPolicy.MAX_ATTEMPTS] runs over at least a
     * day, raises the notice. [tried] files were attempted, [failures] failed.
     */
    fun onCopyRunEnd(context: Context, driveProved: Boolean, tried: Int, failures: Int) {
        val prefs = prefs(context)
        val before = prefs.getInt(KEY_FAILED_RUNS, 0)
        val after = nextFailedRuns(before, driveProved, failures)
        if (after != before) prefs.edit().putInt(KEY_FAILED_RUNS, after).apply()
        if (after == 0) {
            if (before > 0) {
                prefs.edit().remove(KEY_SPELL_START).apply()
                DriveCopyJournal.record(context, "failed-copy spell ENDED after $before fruitless run(s): " + if (failures <= 0) "no copy failed this run" else "Drive holds a newer recording than the newest failure")
            }
            return
        }
        val now = clock()
        val start = prefs.getLong(KEY_SPELL_START, 0L).takeIf { it > 0L } ?: now.also { prefs.edit().putLong(KEY_SPELL_START, it).apply() }
        val why = "of $tried recording(s) tried, $failures failed and nothing newer reached Drive; fruitless run $after of ${CloudCopyPolicy.MAX_ATTEMPTS}, spell ${(now - start) / 3_600_000} h old (needs ${MIN_SPELL_MS / 3_600_000} h)"
        if (!isCopySpellGivingUp(after, now - start)) {
            DriveCopyJournal.record(context, "copies failing, not yet told to the user: $why")
            return
        }
        onGaveUp(context, Reason.COPIES_FAILING, why)
    }

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
                prefs.edit().putBoolean(KEY_POSTED, true).putBoolean(KEY_POSTED_FOR_COPIES, reason == Reason.COPIES_FAILING).apply()
                AppLogger.w(TAG, "Drive backup notice POSTED ($reason): $why")
                DriveCopyJournal.record(context, "user notice POSTED: Drive backup isn't working ($reason); cause: $why")
            }
            else -> DriveCopyJournal.record(context, "user notice already posted for this failure spell; not posted again ($reason)")
        }
    }

    /**
     * Drive was reached ([how]). A copy (the default) proves Drive takes files: it clears any posted notice and ends the
     * failed-copy spell. A listing ([byListing]) clears only a notice about listing; one about failing copies stays until
     * a copy works, or it would flicker away on the next run's listing while every copy still fails (review N6).
     */
    fun onDriveReached(context: Context, how: String, byListing: Boolean = false) {
        val prefs = prefs(context)
        val runs = prefs.getInt(KEY_FAILED_RUNS, 0)
        if (!byListing && runs != 0) {
            prefs.edit().putInt(KEY_FAILED_RUNS, 0).remove(KEY_SPELL_START).apply()
            DriveCopyJournal.record(context, "failed-copy spell ENDED after $runs fruitless run(s): a copy reached Drive ($how)")
        }
        if (byListing && prefs.getBoolean(KEY_POSTED_FOR_COPIES, false)) {
            DriveCopyJournal.record(context, "Drive listed again ($how), but the copies-failing notice stays until a copy works")
            return
        }
        if (decide(prefs.getBoolean(KEY_POSTED, false), Event.REACHED) != Action.CLEAR) return
        SilentFailureNotifier.clearDriveBackupBroken(context)
        prefs.edit().putBoolean(KEY_POSTED, false).putBoolean(KEY_POSTED_FOR_COPIES, false).apply()
        AppLogger.i(TAG, "Drive backup notice CLEARED: $how")
        DriveCopyJournal.record(context, "user notice CLEARED: Drive reached again ($how)")
    }

    /** Drive sync was switched off (device-only / immediate): a posted notice is about a backup that no longer runs. */
    fun onDriveSyncOff(context: Context, why: String) {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_FAILED_RUNS, 0) != 0) prefs.edit().putInt(KEY_FAILED_RUNS, 0).remove(KEY_SPELL_START).apply()
        if (!prefs.getBoolean(KEY_POSTED, false)) return
        SilentFailureNotifier.clearDriveBackupBroken(context)
        prefs.edit().putBoolean(KEY_POSTED, false).putBoolean(KEY_POSTED_FOR_COPIES, false).apply()
        AppLogger.i(TAG, "Drive backup notice CLEARED: Drive sync turned off ($why)")
        DriveCopyJournal.record(context, "user notice CLEARED: Drive sync turned off ($why)")
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
