/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import com.baba.callvault.calls.CallDetection
import com.baba.callvault.calls.CallStateRepository
import com.baba.callvault.calls.UiCall
import com.baba.callvault.dialer.CallActions
import com.baba.callvault.ui.dialer.InCallActivity

/**
 * Default-dialer [InCallService]: receives [Call] objects from Telecom, maps their states to
 * [com.baba.callvault.calls.CallEvent]s via [TelecomCallEventSource], keeps [CallStateRepository]
 * up to date, and launches [InCallActivity] when a call arrives.
 *
 * Emergency calls are never blocked or filtered — the service is a pure observer.
 * [CallDetection.router] is guaranteed to be initialized before this service starts because
 * [com.baba.callvault.CallVaultApplication.onCreate] initializes it before any component binds.
 */
class CallVaultInCallService : InCallService() {

    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onDestroy() {
        super.onDestroy()
        // Prevent leaking the service reference held in CallActions after the service is unbound.
        CallActions.clear()
    }

    override fun onCallAdded(call: Call) {
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) = publish(c)
        }
        callbacks[call] = cb
        call.registerCallback(cb)
        CallActions.activeCall = call
        CallActions.serviceRef = this
        publish(call)
        startActivity(
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onCallRemoved(call: Call) {
        if (call === CallActions.activeCall) {
            CallActions.clear()
            CallStateRepository.update(null)
        }
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        if (!CallDetection.isInitialized) return
        TelecomCallEventSource.submit(CallDetection.router, call) // ENDED
    }

    private fun publish(call: Call) {
        if (!CallDetection.isInitialized) return
        TelecomCallEventSource.submit(CallDetection.router, call)
        val d = call.details
        CallStateRepository.update(
            UiCall(
                number = TelecomCallEventSource.numberOf(d),
                phase = TelecomCallEventSource.phaseOf(call.state),
                direction = TelecomCallEventSource.directionOf(d),
                isEmergency = (d.callProperties and Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) != 0,
                isRecording = CallStateRepository.current.value?.isRecording ?: false,
            ),
        )
    }
}
