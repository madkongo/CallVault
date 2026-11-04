/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import com.baba.callvault.server.RecorderConnection

/**
 * USB debugging as the recorder (shell uid) reads it — the one reader Android 17's redaction never lies to.
 *
 * Why: with Wireless debugging on, the app's own read cannot tell "USB debugging off" from "redacted", so it said
 * UNKNOWN. On the Honor BVL-N59 (2026-11-04) USB debugging was off for two hours, recording hung on Wi-Fi alone, and
 * the "paused off Wi-Fi" notice and its "Turn USB debugging on" button never showed — while the report's own shell
 * read said `adb_enabled as shell: 0`. [UsbDebuggingPolicy] now takes this as the answer when it is fresh.
 *
 * Refreshed off the main thread (it starts `settings get` in the recorder), every keep-alive tick and on every change
 * of the switch; forgotten on a change until re-read, so a stale value never outlives the switch it describes.
 */
object UsbDebuggingShellRead {

    private const val FRESH_MS = 2 * 60_000L

    @Volatile private var value: Boolean? = null
    @Volatile private var readAtMs = 0L

    /** The last shell read if recent, else null (unknown). */
    fun current(now: Long = System.currentTimeMillis()): Boolean? = value.takeIf { now - readAtMs <= FRESH_MS }

    fun forget() {
        value = null
    }

    /** Reads it again through the recorder. Blocking; never on the main thread. Null when it cannot be read. */
    fun refresh(now: Long = System.currentTimeMillis()): Boolean? {
        val service = RecorderConnection.service ?: return null
        val read = runCatching { service.diagnosticDump("setting_adb_enabled", null)?.trim() }.getOrNull()
        val on = parse(read) ?: return null
        value = on
        readAtMs = now
        return on
    }

    fun parse(text: String?): Boolean? = when (text) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
