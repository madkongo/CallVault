/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

/**
 * What the Debug section under Help and about shows, for a given logging state.
 *
 * Share and Save are there in every state. They were hidden while logging was on, so a user in the middle of
 * reproducing a problem had to switch logging off first to find them (owner report 2026-11-20, OP9). The report is
 * worth sending either way: its always-on sections (setup journal, crashes, battery use, Drive copies) are written
 * with logging off, and the log viewer already offered both buttons while logging ran (#71).
 */
internal data class BugReportRows(
    val showLoggingOnWarning: Boolean,
    val showLastSessionHint: Boolean,
    val showShareAndSave: Boolean,
) {
    companion object {
        fun of(isLoggingEnabled: Boolean) = BugReportRows(
            showLoggingOnWarning = isLoggingEnabled,
            // "Logs from your last debug session are ready" is untrue while a session is still running. In ⓘ mode
            // the section also leaves it to the "Help and about" card's ⓘ.
            showLastSessionHint = !isLoggingEnabled,
            showShareAndSave = true,
        )
    }
}
