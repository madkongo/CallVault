/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import com.baba.callvault.services.recording.RecordingPolicy

/**
 * Review C113: a call the user's own settings kept from being recorded (record-only-selected, the ignore list, anonymous
 * or cross-country rules, auto-record off) is a deliberate skip, not a failure. The decision is made in
 * [CallSessionManager]; this says which outcomes count as one, so the call can be noted as seen when it ends and the
 * call-log sweep never reports it as "not recorded". Pure, for testing.
 */
internal object ExcludedCallPolicy {
    fun isDeliberateSkip(action: RecordingPolicy.CarrierAction): Boolean =
        action == RecordingPolicy.CarrierAction.OFFER
}
