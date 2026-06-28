/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile

/**
 * Process-wide bridge that [CallVaultInCallService] populates with the current [Call] and a
 * reference to itself. Audio-routing APIs (mute, speaker) live on [InCallService], not [Call],
 * so both references are needed.
 *
 * [com.baba.callvault.ui.dialer.InCallViewModel] delegates every call action through this object,
 * keeping the ViewModel free of Telecom compile-time dependencies.
 *
 * Thread-safety: fields are @Volatile; all writes originate from the main thread (Telecom
 * callbacks); reads from the UI thread are therefore always consistent.
 */
object CallActions {
    @Volatile var activeCall: Call? = null
    @Volatile var serviceRef: InCallService? = null

    fun answer() {
        activeCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject() {
        activeCall?.reject(false, null)
    }

    fun end() {
        activeCall?.disconnect()
    }

    fun toggleHold() {
        val call = activeCall ?: return
        when (call.state) {
            Call.STATE_ACTIVE  -> call.hold()
            Call.STATE_HOLDING -> call.unhold()
        }
    }

    fun setMuted(muted: Boolean) {
        serviceRef?.setMuted(muted)
    }

    fun setSpeaker(on: Boolean) {
        serviceRef?.setAudioRoute(
            if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        )
    }

    fun sendDtmfTone(digit: Char) {
        activeCall?.playDtmfTone(digit)
        activeCall?.stopDtmfTone()
    }

    /** Called by [CallVaultInCallService.onCallRemoved] to drop stale references. */
    fun clear() {
        activeCall = null
        serviceRef = null
    }
}
