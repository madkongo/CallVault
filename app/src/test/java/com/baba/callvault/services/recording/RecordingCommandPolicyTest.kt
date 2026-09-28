/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.RecordingCommandPolicy.Handling
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the recording service treats each command — whether it posts its notification first, and whether
 * a command with no call behind it may keep the service alive.
 *
 * Three reports (the maintainer's OP12, a Samsung user, a LAVA LXX508) saw "Call in progress — Press to
 * start recording" stay after the call. The LAVA log showed the chain: the STOP re-posted the recording
 * notification on its way out, Android delivered that post after the keep-alive had put "Ready" back, and
 * from then on every Pause, swipe or Record on the leftover started a fresh service with no call — which
 * re-posted it again, or raised "unexpected error".
 */
class RecordingCommandPolicyTest {

    private fun handling(action: String?, hasCall: Boolean) = RecordingCommandPolicy.handling(action, hasCall)

    @Test
    fun a_start_or_standby_always_posts_first_because_android_requires_it() {
        // Both arrive through startForegroundService, which must be answered with startForeground.
        listOf(RecordingForegroundService.ACTION_START_RECORDING, RecordingForegroundService.ACTION_STANDBY)
            .forEach { action ->
                assertEquals(action, Handling.POST_THEN_HANDLE, handling(action, hasCall = true))
                assertEquals(action, Handling.POST_THEN_HANDLE, handling(action, hasCall = false))
            }
    }

    @Test
    fun a_stop_posts_nothing() {
        // The post a stop used to make is the one that landed late and stuck.
        assertEquals(Handling.HANDLE_ONLY, handling(RecordingForegroundService.ACTION_STOP_RECORDING, hasCall = true))
        assertEquals(Handling.HANDLE_ONLY, handling(RecordingForegroundService.ACTION_STOP_RECORDING, hasCall = false))
    }

    @Test
    fun notification_actions_during_a_call_are_handled_as_before() {
        listOf(
            RecordingForegroundService.ACTION_NOTIFICATION_DISMISSED,
            RecordingForegroundService.ACTION_PAUSE_RECORDING,
            RecordingForegroundService.ACTION_RESUME_RECORDING,
            RecordingForegroundService.ACTION_FLAG_MOMENT,
        ).forEach { action -> assertEquals(action, Handling.HANDLE_ONLY, handling(action, hasCall = true)) }
    }

    @Test
    fun a_manual_record_during_a_call_posts_preparing_first() {
        assertEquals(Handling.POST_THEN_HANDLE, handling(RecordingForegroundService.ACTION_MANUAL_START, hasCall = true))
    }

    @Test
    fun any_notification_action_with_no_call_behind_it_ends_the_service() {
        // The LAVA sequence: Pause 30 s after the call, then a swipe, then Record.
        listOf(
            RecordingForegroundService.ACTION_NOTIFICATION_DISMISSED,
            RecordingForegroundService.ACTION_PAUSE_RECORDING,
            RecordingForegroundService.ACTION_RESUME_RECORDING,
            RecordingForegroundService.ACTION_FLAG_MOMENT,
            RecordingForegroundService.ACTION_MANUAL_START,
            null,
        ).forEach { action -> assertEquals("$action", Handling.END_ORPHAN, handling(action, hasCall = false)) }
    }
}
