/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** What CallVault owes a **running Shizuku server** when it is about to restart `adbd`. */
enum class ShizukuChurnAdvice {
    /** No Shizuku server is running, so the restart costs nobody anything. Say nothing. */
    NOTHING,

    /** A dialog is on screen and can carry the warning before the user commits to it. */
    WARN_FIRST,

    /** No dialog to warn in: the restart happens and the user is told afterwards, in a notification. */
    TELL_AFTER,
}

/**
 * Whether a deliberate `adbd` restart has to be explained, because someone else's Shizuku will die in it.
 *
 * **The hazard.** Shizuku's server is a shell-uid process hosted by `adbd`, so anything that restarts
 * `adbd` kills it, and nothing restarts it — R10 in `docs/dev-notes/2026-09-14-debugging-switches-model.md`,
 * reproduced on the OP9 as S1a and again on 2026-09-18 (arming off-Wi-Fi recording gave `adbd` a new pid
 * and Shizuku's server was gone and never came back). Shizuku is **not ours**: people run it for app
 * managers, ad-blockers and file managers, and #39's reporter watched his stop "within a second" with no
 * idea what had done it.
 *
 * **What this does NOT do.** It never cancels the restart. Recording is the app's job and a call that is
 * not recorded cannot be recovered, while a Shizuku server can be started again in two taps. So the
 * decision here is only about *telling the user*, never about backing out — the one place CallVault
 * declines a restart outright is [AdbdRevivalPolicy], and only because a live Shizuku server proves the
 * restart was unnecessary in the first place.
 *
 * Kept free of `Context` so every branch is tested.
 */
object ShizukuChurnPolicy {

    /**
     * @param shizukuServerRunning whether a Shizuku server answers right now (`Shizuku.pingBinder()`).
     * @param underDialog whether the restart is being run from a dialog the user is looking at, which can
     *   carry the warning up front. A notification on top of that dialog would only repeat it.
     */
    fun decide(shizukuServerRunning: Boolean, underDialog: Boolean): ShizukuChurnAdvice = when {
        !shizukuServerRunning -> ShizukuChurnAdvice.NOTHING
        underDialog -> ShizukuChurnAdvice.WARN_FIRST
        else -> ShizukuChurnAdvice.TELL_AFTER
    }
}
