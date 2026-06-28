/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.dialer

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.baba.callvault.calls.CallDirection
import com.baba.callvault.calls.CallStateRepository
import com.baba.callvault.calls.UiCall
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.dialer.CallActions
import com.baba.callvault.services.recording.RecordingForegroundService
import kotlinx.coroutines.flow.StateFlow

/**
 * Pure, JVM-testable helper for in-call display labels.
 * Lives next to [InCallViewModel] so the unit test can access it without Android context.
 */
object InCallLabels {
    /**
     * Returns the phone number to display for [call], falling back to [unknown] when the number
     * is null or blank.
     */
    fun displayNumber(call: UiCall?, unknown: String): String =
        call?.number?.takeIf { it.isNotBlank() } ?: unknown
}

/**
 * ViewModel for the in-call screen.
 *
 * Exposes [current] from [CallStateRepository] and delegates all call actions to [CallActions],
 * keeping Telecom internals out of the Compose layer.
 *
 * Recording toggle concern (on-device-unverified): the manual start sends
 * [RecordingForegroundService.ACTION_MANUAL_START] with metadata derived from the current [UiCall].
 * This bypasses the 500ms verification window in CallSessionManager and skips contact-filter
 * evaluation — correct for an explicit user action. The metadata is un-enriched (no E.164
 * standardisation); enrichment happens asynchronously after call end regardless.
 */
class InCallViewModel(application: Application) : AndroidViewModel(application) {

    /** Mirrors [CallStateRepository.current]; observed by the Compose layer. */
    val current: StateFlow<UiCall?> = CallStateRepository.current

    fun answer() = CallActions.answer()
    fun reject() = CallActions.reject()
    fun end() = CallActions.end()
    fun hold() = CallActions.toggleHold()
    fun setMuted(muted: Boolean) = CallActions.setMuted(muted)
    fun setSpeaker(on: Boolean) = CallActions.setSpeaker(on)
    fun sendDtmf(digit: Char) = CallActions.sendDtmfTone(digit)

    /**
     * Starts or stops recording for the current call.
     *
     * Start path: fires [RecordingForegroundService.ACTION_MANUAL_START] with minimal
     * [RecordingMetadata] built from the current [UiCall].
     *
     * Stop path: fires [RecordingForegroundService.ACTION_STOP_RECORDING], matching what
     * CallSessionManager sends on call end.
     */
    fun toggleRecord() {
        val call = current.value ?: return
        val ctx = getApplication<Application>().applicationContext
        if (call.isRecording) {
            ctx.startService(
                Intent(ctx, RecordingForegroundService::class.java)
                    .setAction(RecordingForegroundService.ACTION_STOP_RECORDING),
            )
        } else {
            val direction = when (call.direction) {
                CallDirection.INCOMING -> RecordingDirection.INCOMING
                else -> RecordingDirection.OUTGOING
            }
            val metadata = RecordingMetadata(rawPhoneNumber = call.number, direction = direction)
            ctx.startForegroundService(
                Intent(ctx, RecordingForegroundService::class.java)
                    .setAction(RecordingForegroundService.ACTION_MANUAL_START)
                    .putExtra(RecordingMetadata.EXTRA_METADATA, metadata),
            )
        }
    }
}
