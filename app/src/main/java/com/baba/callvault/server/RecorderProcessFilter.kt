/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Tells a recorder HOST process from a process that merely has the recorder's class name on its command line.
 *
 * `pgrep -f com.baba.callvault.server.RecorderServer` matches the whole command line, so it also matches the adb shell
 * that launched the daemon (`sh -c "setsid sh -c 'CLASSPATH=… exec app_process / …RecorderServer …' & sleep 3"`) for the
 * few seconds it stays alive. Killing that shell is not "clearing a stale recorder": it is the launcher of the host that
 * is calling (BabApps #95, pid 8852 beside the new host 8854). A recorder host's argv[0] is `app_process` (our own
 * daemon) or `<package>:recorder` (the Shizuku-hosted one); a launcher shell's is `sh` / `setsid`.
 */
object RecorderProcessFilter {

    /** [cmdline] is `/proc/<pid>/cmdline` (NUL-separated). Unreadable (null/blank) counts as a host, as before. */
    fun isRecorderHost(cmdline: String?): Boolean {
        if (cmdline.isNullOrBlank()) return true
        val argv0 = cmdline.substringBefore('\u0000').trim().substringAfterLast('/')
        return argv0.startsWith("app_process") || argv0.endsWith(":recorder")
    }
}
