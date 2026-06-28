/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.dialer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baba.callvault.calls.CallEvent
import com.baba.callvault.ui.theme.CallVaultTheme

/**
 * Full-screen in-call Activity.
 *
 * Shown over the lock screen ([setShowWhenLocked]) and wakes the display ([setTurnScreenOn])
 * so an incoming call is immediately visible. The Activity auto-finishes when the call ends
 * ([CallEvent.Phase.ENDED]) or when [com.baba.callvault.calls.CallStateRepository.current] drops
 * to null (call removed by the system).
 *
 * All call actions are delegated to [InCallViewModel], which routes them through
 * [com.baba.callvault.dialer.CallActions].
 */
class InCallActivity : ComponentActivity() {

    private val vm: InCallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            CallVaultTheme {
                val call by vm.current.collectAsStateWithLifecycle()

                val shouldFinish = call == null || call?.phase == CallEvent.Phase.ENDED
                LaunchedEffect(shouldFinish) { if (shouldFinish) finish() }
                if (shouldFinish) return@CallVaultTheme

                InCallScreen(
                    call = call,
                    onAnswer = { vm.answer() },
                    onReject = { vm.reject() },
                    onEnd = { vm.end() },
                    onHold = { vm.hold() },
                    onMute = { muted -> vm.setMuted(muted) },
                    onSpeaker = { on -> vm.setSpeaker(on) },
                    onToggleRecord = { vm.toggleRecord() },
                    onDtmf = { digit -> vm.sendDtmf(digit) },
                )
            }
        }
    }
}
