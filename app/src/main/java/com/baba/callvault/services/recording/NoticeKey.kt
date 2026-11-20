/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification

/**
 * What a posted notification shows, as one comparable string, so the keep-alive can skip a re-post that would change
 * nothing (review R11): each post wakes system UI and every notification listener. Compared against what is actually
 * posted rather than against what was last sent, so a notice replaced by a recording, swiped away, or rewritten by
 * anyone is always put back.
 */
object NoticeKey {

    fun of(notification: Notification): String {
        val extras = notification.extras
        val actions = notification.actions?.joinToString("\u0001") { it.title?.toString().orEmpty() }.orEmpty()
        return listOf(
            notification.channelId.orEmpty(),
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            actions,
        ).joinToString("\u0000")
    }

    /** True unless [posted] is exactly what [next] would show. A notice that is not posted always needs posting. */
    fun shouldPost(posted: Notification?, next: Notification): Boolean =
        posted == null || of(posted) != of(next)
}
