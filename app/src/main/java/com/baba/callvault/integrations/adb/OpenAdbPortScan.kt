/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package com.baba.callvault.integrations.adb

/**
 * One pass over the already-open adb tcp candidates: probe each, connect to the first that answers as classic
 * adbd. Free of Android types: clock, probe and connect are lambdas, so every branch is tested.
 *
 * Bounded so it fits inside AdbShell's 60 s connect-entry limit even in offline mode's worst case: probing
 * stops once [run]'s `probeBudgetMs` has passed, and there is **at most one** real connect (the first port that
 * answers as adb), so the whole pass is at most the probe budget + one probe + one connect cap.
 */
internal object OpenAdbPortScan {

    enum class Outcome {
        CONNECTED,
        /** Another thread connected while we were looking; nothing of ours was used. */
        ALREADY_CONNECTED,
        /** A port answered as adbd, but the one connect over it failed — possibly transient, not a miss. */
        ADB_ANSWERED_NOT_CONNECTED,
        /** The probe budget ran out before every port was asked: not a miss either. */
        BUDGET_SPENT,
        /** Every port was closed or not adb: a miss worth remembering. */
        NOTHING_ANSWERED,
    }

    fun run(
        ports: List<Int>,
        probeBudgetMs: Long,
        now: () -> Long,
        isConnected: () -> Boolean,
        probe: (Int) -> PortProbe,
        connect: (Int) -> Boolean,
    ): Outcome {
        val deadline = now() + probeBudgetMs
        for (port in ports) {
            if (isConnected()) return Outcome.ALREADY_CONNECTED
            if (now() >= deadline) return Outcome.BUDGET_SPENT
            if (!LoopbackPorts.isAlreadyOpenAdb(probe(port))) continue
            return if (connect(port)) Outcome.CONNECTED else Outcome.ADB_ANSWERED_NOT_CONNECTED
        }
        return Outcome.NOTHING_ANSWERED
    }
}
