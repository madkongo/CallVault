/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * The launcher's "clear stale daemons before launching" step, with everything it touches behind [Env] so the decision
 * can be tested: a spared daemon must see no kill and no launch, an idle one must be killed.
 */
object StaleDaemonClearing {

    data class Staging(val path: String?, val growing: Boolean, val detail: String)

    interface Env {
        fun staging(): Staging

        /** Whether a call (any kind) is in progress by the audio mode; null when it could not be read. */
        fun inCall(): Boolean?

        /** What the daemon scan printed, or null when the shell failed. */
        fun probeOutput(): String?
        fun kill()
        fun log(line: String)
        fun warn(line: String)

        /** False once a spare has been withdrawn (see [SpareMemory]). */
        fun spareAllowed(): Boolean
        fun noteSpared()
    }

    /** True when the caller may launch a recorder; false when a recording daemon was left alone and nothing may be launched. */
    fun clear(env: Env): Boolean {
        val staging = env.staging()
        val inCall = env.inCall()
        if (staging.growing && inCall == null) env.log("call state unreadable; treating a growing session as a call in progress")
        val probe = if (staging.growing) StaleDaemonPolicy.parseProbe(env.probeOutput()) else StaleDaemonPolicy.Probe.Unknown
        val facts = "inCall=${inCall ?: "unknown"} staging=${staging.path ?: "none"} (${staging.detail}) hosts=$probe"
        val action = StaleDaemonPolicy.decide(staging.growing, probe, inCall)
        if (action == StaleDaemonPolicy.Action.SPARE && !env.spareAllowed()) {
            env.warn("Not sparing the recording daemon any more (its hand-over was refused or never came); killing it, its file may be unfinalised; $facts")
            env.kill()
            return true
        }
        return when (action) {
            StaleDaemonPolicy.Action.SPARE -> {
                env.noteSpared()
                env.log("A session is being recorded by a running daemon: not killing it, not launching another; $facts")
                false
            }
            StaleDaemonPolicy.Action.KILL_THEN_LAUNCH -> {
                if (staging.growing && inCall == false) {
                    env.warn("killing a recording orphan; file will be unfinalised; rescue expected; $facts")
                } else {
                    env.log("killing any stale daemon before the launch; $facts")
                }
                env.kill()
                true
            }
        }
    }
}
