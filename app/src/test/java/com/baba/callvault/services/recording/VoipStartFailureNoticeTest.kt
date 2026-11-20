/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N21 rework (4a/4b): the "app call not recorded" notice for a capture that never started goes out only when
 * the recorder said why it failed, and not for a call too short to have been one (the same 3 s the stub rule uses).
 * An empty capture with no reason — a sub-second call, a recorder that died — gets the always-on log line only.
 */
class VoipStartFailureNoticeTest {

    @Test
    fun a_reason_from_the_recorder_on_a_real_length_call_is_told() {
        assertTrue(VoipMissPolicy.tellStartFailure("IllegalStateException: VoIP far-party sink unavailable", 49_000L))
    }

    @Test
    fun an_unknown_length_with_a_reason_is_told() {
        assertTrue(VoipMissPolicy.tellStartFailure("IllegalStateException: x", null))
    }

    @Test
    fun no_reason_is_never_told() {
        assertFalse(VoipMissPolicy.tellStartFailure(null, 49_000L))
        assertFalse(VoipMissPolicy.tellStartFailure(null, null))
    }

    @Test
    fun a_call_under_three_seconds_is_not_told() {
        assertFalse(VoipMissPolicy.tellStartFailure("IllegalStateException: x", VoipStubPolicy.TOO_SHORT_TO_JUDGE_MS - 1))
        assertTrue(VoipMissPolicy.tellStartFailure("IllegalStateException: x", VoipStubPolicy.TOO_SHORT_TO_JUDGE_MS))
    }
}
