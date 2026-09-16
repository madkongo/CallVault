/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

/**
 * A request, carried on the Intent that opens `MainActivity`, to land on one transcript's reading view.
 *
 * The sender is the "transcript ready" notification, which is the whole point of it existing: the
 * case it was built for is a shared voice note, where the user left CallVault before the words were
 * written and the notification is the only delivery there is. Landing them on a list and asking them
 * to find it would be the app telling somebody a thing happened somewhere else.
 *
 * **Separate from [OpenRecordingRequest] rather than sharing its extra**, though both are a display
 * name: the two open different screens — that one opens a recording's own screen, this one opens the
 * reading view — and one extra read two ways would need [NotificationDestination] threaded all the
 * way down to Home to decide which. Two extras, one meaning each.
 *
 * **Separate from [NotificationDestination]** for the reason that one gives itself: that is a closed
 * set of places resolved from a key, and this is an open-ended name of one row. The destination still
 * carries the *section*; this only says which transcript on it.
 */
object OpenTranscriptRequest {

    /** Intent extra carrying the transcript's display name. Namespaced: an Intent is public API. */
    const val EXTRA = "com.baba.callvault.extra.OPEN_TRANSCRIPT"

    /**
     * The transcript to open, or null when nothing was asked for.
     *
     * [relaunchedFromHistory] is the trap, and it is the same one [OpenRecordingRequest] guards.
     * Android hands the task's original Intent back when the app is reopened from the recents list,
     * extras and all — so without this check, one tap on "Transcript ready" would re-open that same
     * transcript on every later return from recents, days later, over whatever the user was doing.
     *
     * A blank name is nothing rather than a name: it would be waited for in a list it can never
     * appear in, leaving the reading view to open on a row that does not exist.
     */
    fun fromIntentExtra(name: String?, relaunchedFromHistory: Boolean): String? {
        if (relaunchedFromHistory) return null
        return name?.takeIf { it.isNotBlank() }
    }
}
