/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.PrivilegedMode

/**
 * Whether a recording may take the resilient (handoff) path.
 *
 * **2026-10-19: Shizuku mode is no longer ruled out.** The failure below came from the Shizuku host
 * presenting our package under uid 2000; `ShellAttribution` fixed that (measured on the OP9, where carrier
 * and VoIP calls now record directly in the Shizuku host), and `HandoffSource` now refuses to hand over a
 * record that did not start, so the old silent-0-byte shape falls back instead. History, kept as written:
 *
 * **Why this was not simply the user's preference.** The handoff path exists so a recording survives the
 * daemon dying mid-call: the *daemon* creates the privileged `AudioRecord` and hands the live capture to
 * the app, which reads the ring buffer itself. That requires the daemon's process to be able to record —
 * and a **Shizuku-hosted process cannot**. Measured on a OnePlus 9 Pro on 2026-08-24:
 *
 * ```
 * Direct capture unavailable, falling back to scrcpy: AudioRecord failed to enter RECORDING state
 * ```
 *
 * The daemon's own capture survives that by falling back to scrcpy, which spawns a separate process. The
 * handoff cannot fall back: the app has already been handed a record that will never produce a frame, so
 * it encodes nothing and writes a **0-byte file while reporting success**. That is exactly what a real
 * call produced on that phone before this existed.
 *
 * ShizuCallRecorder — which has run on Shizuku far longer than CallVault has — captures **only** through
 * scrcpy. Same conclusion, reached from the other direction.
 */
object HandoffPolicy {

    /**
     * @param enabled the user's "resilient recording" opt-in.
     * @param sourceSupported whether this audio source can be captured by a Java `AudioRecord` at all.
     * @param mode where privileges come from. Both hosts can take this path now; kept as a parameter so
     *   the decision stays in one place if a mode ever has to be ruled out again.
     * @param appGetsPaused this phone has been seen pausing CallVault's process ([AppPausePolicy]). The handoff
     *   only records while the app keeps reading, so there the recorder process must own the capture: a paused
     *   app cut a 43 s call to 4.4 s on an Honor BVL-N59 (2026-11-05). False on every phone that never paused it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isUsable(
        enabled: Boolean,
        sourceSupported: Boolean,
        mode: PrivilegedMode,
        appGetsPaused: Boolean = false,
    ): Boolean = enabled && sourceSupported && !appGetsPaused
}
