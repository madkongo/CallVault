/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

/**
 * When the "update available" notice is posted (review C36).
 *
 * It is posted once per tag. Until C36 it shared id 4716 with the "cannot record" warning, which the keep-alive
 * cleared on every ready tick, so the once-per-tag memory outlived a notice that was already gone and a manual-update
 * user never saw that release. The notice now has its own id; [ownIdAdopted] false means this install has not posted it
 * under that id yet, so a notice the old shared id may have wiped is posted once more.
 */
object UpdateNoticePolicy {
    fun shouldPost(lastNotifiedTag: String?, tag: String, ownIdAdopted: Boolean): Boolean =
        !ownIdAdopted || lastNotifiedTag != tag
}
