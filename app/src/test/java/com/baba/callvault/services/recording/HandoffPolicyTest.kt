/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a recording may use the resilient (handoff) path.
 *
 * **Measured on a OnePlus 9 Pro, 2026-08-24.** A Shizuku-hosted process cannot record:
 *
 * ```
 * Direct capture unavailable, falling back to scrcpy: AudioRecord failed to enter RECORDING state
 * ```
 *
 * The daemon's own `startWithFallback` survives that by falling back to scrcpy — but the **handoff**
 * path cannot, because the whole point of it is that the *daemon* creates the `AudioRecord` and hands
 * the live capture to the app. A record that never reaches RECORDING state produces no frames, so the
 * app encodes nothing and writes a **0-byte file while reporting success** — which is exactly what a
 * real call on that phone produced.
 *
 * ShizuCallRecorder, which has run on Shizuku far longer than we have, only ever captures through
 * scrcpy. This is the same conclusion arrived at independently.
 */
class HandoffPolicyTest {

    @Test
    fun standalone_uses_handoff_when_it_is_enabled_and_the_source_supports_it() {
        assertTrue(
            HandoffPolicy.isUsable(
                enabled = true, sourceSupported = true, mode = PrivilegedMode.STANDALONE
            )
        )
    }

    @Test
    fun the_opt_in_still_governs() {
        assertFalse(
            HandoffPolicy.isUsable(
                enabled = false, sourceSupported = true, mode = PrivilegedMode.STANDALONE
            )
        )
    }

    @Test
    fun an_unsupported_source_still_rules_it_out() {
        assertFalse(
            HandoffPolicy.isUsable(
                enabled = true, sourceSupported = false, mode = PrivilegedMode.STANDALONE
            )
        )
    }

    @Test
    fun shizuku_mode_uses_handoff_now_that_its_host_can_record() {
        // Since ShellAttribution the Shizuku host records directly; the guard against silent empty
        // recordings moved to HandoffSource, which refuses a record that did not start.
        assertTrue(
            HandoffPolicy.isUsable(
                enabled = true, sourceSupported = true, mode = PrivilegedMode.SHIZUKU
            )
        )
    }

    @Test
    fun a_phone_that_pauses_the_app_records_through_the_recorder_instead() {
        assertFalse(
            HandoffPolicy.isUsable(
                enabled = true, sourceSupported = true, mode = PrivilegedMode.STANDALONE, appGetsPaused = true
            )
        )
    }
}
