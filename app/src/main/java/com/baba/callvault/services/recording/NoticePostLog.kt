/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * The log line for a post of the recorder-status notification (id 4720): which notice and why. Returns a line only
 * when the notice or the reason differs from the one logged last, so a steady state never spams the log (R11).
 * Logging only; nothing reads it back.
 */
class NoticePostLog {
    private var lastNotice: String? = null
    private var lastWhy: String? = null

    @Synchronized
    fun lineIfChanged(notice: String, why: String): String? {
        if (notice == lastNotice && why == lastWhy) return null
        val previous = lastNotice
        lastNotice = notice
        lastWhy = why
        return "Recorder-status notification posted: notice=$notice (previous=${previous ?: "none"}), why=$why"
    }
}
