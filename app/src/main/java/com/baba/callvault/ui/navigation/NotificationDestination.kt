/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

/**
 * What a notification was about, carried on the Intent that opens MainActivity.
 *
 * Every notification that opens the app does so with a bare Intent today, and gets away with it
 * because there is one screen and it is the status card. That is the whole safety net: "recording is
 * broken" works as a notification only because tapping it lands on the thing that explains it. Once
 * the app reopens the section you were last in, the same tap can land on a transcript list with no
 * sign of the problem — silently, since nothing crashes and nothing logs.
 *
 * So the notification says what it was about, and the app is free to act on it. Nothing resolves
 * anywhere but Home yet; the point of landing it now is that the sections can be split without the
 * notifications quietly becoming wrong in the same commit.
 *
 * @param key         The stable string put on the Intent. Stable because a PendingIntent created by
 *                    an older build can still be sitting in the shade when a newer one reads it.
 * @param requestCode The PendingIntent request code to build this destination's Intent with.
 *                    Load-bearing: PendingIntent identity ignores extras (`Intent.filterEquals`
 *                    compares action, data, type, package, component and categories — not extras and
 *                    not flags), so two of these Intents sharing a request code are ONE PendingIntent,
 *                    and `FLAG_UPDATE_CURRENT` then hands whichever was posted last its destination to
 *                    both. Two of the four already shared request code 0. One code per destination
 *                    keeps them apart by construction.
 */
enum class NotificationDestination(val key: String, val requestCode: Int) {

    /**
     * Nothing asked for anything: a launcher open, a notification from a build before this existed,
     * or a destination already acted on. Never written onto an Intent — it is what the absence of
     * one reads as.
     */
    None("none", 0),

    /** Something about recording is wrong and the status card is where it is explained. */
    Status("status", 5001),

    /** A new release is available; the update offer is on Home. */
    Update("update", 5002),

    /** ADB pairing finished and onboarding can continue. */
    Pairing("pairing", 5003),

    /** The standing "debug logging is on" reminder. */
    Debug("debug", 5004);

    companion object {

        /** Intent extra carrying [key]. Namespaced, because an Intent into an Activity is public API. */
        const val EXTRA = "com.baba.callvault.extra.DESTINATION"

        /**
         * Reads a destination off an Intent's extra.
         *
         * Unknown and missing both mean [None] rather than a guess: an unrecognised key is a
         * PendingIntent minted by a build that knew about a section this one does not, and sending
         * someone somewhere arbitrary is worse than sending them nowhere in particular.
         *
         * [relaunchedFromHistory] is the trap this exists to close. Android hands the task's original
         * Intent back when the app is reopened from the recents list, extras and all — so without
         * this check, tapping a "recording is broken" notification once would make every later return
         * from recents re-navigate to the status card, long after the problem was dealt with. The
         * system marks those relaunches, and a marked one is not a fresh request.
         *
         * @param key                   The value of [EXTRA], or null when the Intent carries none.
         * @param relaunchedFromHistory Whether the Intent arrived with `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`.
         * @return The requested destination, or [None] when nothing valid was requested.
         */
        fun fromIntentExtra(key: String?, relaunchedFromHistory: Boolean): NotificationDestination {
            if (relaunchedFromHistory) return None
            return entries.firstOrNull { it.key == key } ?: None
        }
    }
}
