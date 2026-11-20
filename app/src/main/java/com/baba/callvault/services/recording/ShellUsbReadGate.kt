/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.integrations.adb.ShellReadNeed

/**
 * Carries the keep-alive's per-tick [ShellReadNeed] and says when to log it: once per change, never every tick.
 *
 * Every shell read is a fork+exec of `settings get` in the shell-uid recorder, charged to "Android System". It used to
 * run every 60 s tick while the recorder was up; now only for [ShellReadNeed.READ]. Nothing is forgotten here — only a
 * switch change forgets ([com.baba.callvault.integrations.adb.UsbDebuggingShellRead.forget]).
 */
internal class ShellUsbReadGate {

    data class Step(val need: ShellReadNeed, val log: String?)

    private var last: ShellReadNeed? = null

    @Synchronized
    fun decide(need: ShellReadNeed): Step {
        val log = if (need == last) null else when (need) {
            ShellReadNeed.READ ->
                "USB debugging: only the recorder's read can tell (Wireless debugging on, or adbd stopped/unreadable); " +
                    "asking it each keep-alive tick"
            ShellReadNeed.R1_DECIDES ->
                "USB debugging: adbd up with Wireless debugging off proves it on (R1); not asking the recorder"
            ShellReadNeed.SETTING_DECIDES ->
                "USB debugging: the setting reads on; not asking the recorder"
        }
        last = need
        return Step(need, log)
    }
}
