/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.io.IOException
import java.io.InputStream

/**
 * Reads a shell stream a piece at a time instead of to end-of-file (C80, C103).
 *
 * libadb 3.1.1 reports a peer close in one of two bad ways: an exception that throws away what was already
 * queued ("Stream closed."), or a wait that never ends while data is still queued. A `readText()` therefore
 * either loses the answer or hangs. Reading piece by piece keeps whatever arrived, and lets the caller stop
 * the moment it has what it needs.
 */
internal object ShellOutputRead {

    private const val CHUNK_BYTES = 1024

    /**
     * Appends what [input] delivers to [sink] until end-of-file, an [IOException] (what arrived stays in [sink]),
     * or [stopWhen] says the text so far is enough. The sink is shared so a caller that gives up on a blocked read
     * (its own time cap) can still read what had arrived. Blocks only as long as [input]'s own read does.
     *
     * @return true when the read ended because [stopWhen] was satisfied.
     */
    fun readInto(input: InputStream, sink: StringBuffer, stopWhen: (String) -> Boolean = { false }): Boolean {
        val buf = ByteArray(CHUNK_BYTES)
        while (true) {
            val n = try {
                input.read(buf)
            } catch (_: IOException) {
                return stopWhen(sink.toString())
            }
            if (n < 0) return stopWhen(sink.toString())
            sink.append(String(buf, 0, n, Charsets.UTF_8))
            if (stopWhen(sink.toString())) return true
        }
    }
}
