/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

/**
 * A request, carried on the Intent that opens `MainActivity`, to land on one recording.
 *
 * There is exactly one sender today: the share target, whose Open button has to reach the file the
 * user just shared. Without it, Open lands on whichever section they were last in — which, on the
 * emulator, was a page of summaries with no sign of the voice note that had just arrived. That is
 * the app telling somebody a thing happened somewhere else, which is the failure the picker path
 * was built to avoid by opening the recording's own screen.
 *
 * Separate from [NotificationDestination] rather than folded into it: that is a closed set of
 * places, resolved from a key, and this is an open-ended name of one row. What they do share is the
 * trap below, which is why the guard is written out here too rather than assumed.
 */
object OpenRecordingRequest {

    /** Intent extra carrying the recording's display name. Namespaced: an Intent is public API. */
    const val EXTRA = "com.baba.callvault.extra.OPEN_RECORDING"

    /**
     * The recording to open, or null when nothing was asked for.
     *
     * [relaunchedFromHistory] is the trap. Android hands the task's original Intent back when the
     * app is reopened from the recents list, extras and all — so without this check, one share
     * would make every later return from recents re-open that same recording, days later, over
     * whatever the user was actually doing.
     *
     * A blank name is nothing rather than a name: it would be looked for in the list, never found,
     * and leave the app waiting for a row that cannot arrive.
     */
    fun fromIntentExtra(name: String?, relaunchedFromHistory: Boolean): String? {
        if (relaunchedFromHistory) return null
        return name?.takeIf { it.isNotBlank() }
    }
}
