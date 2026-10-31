/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package com.baba.callvault.integrations.adb

/**
 * One pass over the already-open adb tcp candidates: probe each, connect to one that answers as classic adbd.
 * Free of Android types: probe and connect are lambdas, so every branch is tested.
 */
internal object OpenAdbPortScan {

    enum class Outcome {
        CONNECTED,
        /** Some port answered as adbd, but no connect over it worked — possibly transient, not a miss. */
        ADB_ANSWERED_NOT_CONNECTED,
        /** Every port was closed or not adb: a miss worth remembering. */
        NOTHING_ANSWERED,
    }

    fun run(ports: List<Int>, probe: (Int) -> PortProbe, connect: (Int) -> Boolean): Outcome {
        var adbAnswered = false
        for (port in ports) {
            if (!LoopbackPorts.isAlreadyOpenAdb(probe(port))) continue
            adbAnswered = true
            if (connect(port)) return Outcome.CONNECTED
        }
        return if (adbAnswered) Outcome.ADB_ANSWERED_NOT_CONNECTED else Outcome.NOTHING_ANSWERED
    }
}
