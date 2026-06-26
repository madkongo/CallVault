/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

import com.baba.callvault.calls.CallEvent
import com.baba.callvault.calls.CallEventRouter
import com.baba.callvault.calls.DetectionMode

/** Adapts the existing PhoneStateReceiver/CallMonitorService path to the CallEventRouter. */
class BroadcastCallEventSource(private val router: CallEventRouter) {
    fun emit(event: CallEvent) = router.submit(DetectionMode.BROADCAST, event)
}
