/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

import android.telecom.Call
import com.baba.callvault.calls.CallDirection
import com.baba.callvault.calls.CallEvent
import com.baba.callvault.calls.CallEventRouter
import com.baba.callvault.calls.DetectionMode

/**
 * Pure mapping helpers: translate Telecom [Call] state/details into normalized [CallEvent]s and
 * forward them to the shared [CallEventRouter]. Kept as a separate object so the mapping functions
 * could be unit-tested without an [android.telecom.InCallService] in a future task.
 *
 * API-level notes (minSdk 30):
 *  - [Call.Details.callDirection] was added in API 29 — safe at minSdk 30.
 *  - [Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE] was added in API 28 — safe.
 */
object TelecomCallEventSource {

    fun phaseOf(state: Int): CallEvent.Phase = when (state) {
        Call.STATE_RINGING -> CallEvent.Phase.RINGING
        Call.STATE_DIALING, Call.STATE_CONNECTING -> CallEvent.Phase.DIALING
        Call.STATE_ACTIVE -> CallEvent.Phase.ACTIVE
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallEvent.Phase.ENDED
        else -> CallEvent.Phase.ACTIVE
    }

    fun directionOf(details: Call.Details): CallDirection = when (details.callDirection) {
        Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
        Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
        else -> CallDirection.UNKNOWN
    }

    fun numberOf(details: Call.Details): String? =
        details.handle?.schemeSpecificPart

    fun submit(router: CallEventRouter, call: Call) {
        val d = call.details
        router.submit(
            DetectionMode.TELECOM,
            CallEvent(
                phaseOf(call.state),
                numberOf(d),
                directionOf(d),
                isEmergency = (d.callProperties and Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) != 0,
            ),
        )
    }
}
