/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * Words that the recorder's launch and keep-alive log lines are built from AND that the battery section counts
 * ([com.baba.callvault.system.diagnostics.battery.RecorderActivityCounts]). One definition for both, so rewording a
 * log line cannot silently turn a count into zero.
 */
object RecorderLogMarkers {
    const val LAUNCHER_TAG = "CV:RecorderLauncher"
    const val KEEP_ALIVE_TAG = "CV:DaemonKeepAlive"

    /** The ADB launcher starting one attempt. */
    const val LAUNCH = "launching recorder daemon"

    /** The keep-alive deciding to relaunch; "relaunch keeps timing out" and "relaunch failed" do not contain it. */
    const val RELAUNCH = "relaunching"

    /** The keep-alive giving up on the current ADB connection after relaunches kept timing out. */
    const val ADB_REBUILD = "rebuilding the ADB connection"
}
