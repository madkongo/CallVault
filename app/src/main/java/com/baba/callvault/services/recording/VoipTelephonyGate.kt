/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.baba.callvault.utils.AppLogger

/**
 * Keeps the app-call (VoIP) path off a carrier call — and only off a carrier call.
 *
 * [VoipCallDetector] recognises an app call by one signal: the audio mode being
 * `MODE_IN_COMMUNICATION`. That is a ROM convention rather than a rule: Wi-Fi calling and some VoLTE
 * stacks carry the call over IMS and can present the same way. On such a device the app-call path
 * would treat the user's phone call as an app call — a second recording of a call already being
 * recorded properly, and a plain `MIC` capture contending with the dialer for the microphone. This
 * gate exists to prevent that, and still does.
 *
 * **What changed, and why it had to.** The gate originally asked
 * [TelephonyManager.getCallState], which cannot answer the question. Android documents it as
 * returning "the state of all calls on the device... not only calls in the Telephony stack, but also
 * calls via other ConnectionService implementations" — so an *app* call that registers with Telecom
 * reads as OFFHOOK exactly like a phone call. AOSP's `PhoneStateBroadcaster` confirms it from the
 * other side: it excludes only *external* calls from the phone-state broadcast, and takes care to
 * strip the phone number for self-managed ones, which would be pointless if they never arrived.
 *
 * That is not an OEM deviation, it is the platform contract, and it made this gate refuse to record
 * the very calls the VoIP feature exists for. A WhatsApp call on One UI reports OFFHOOK; a Galaxy
 * S24 FE recorded such calls perfectly on 2026-07-26, nine days before this gate shipped and stopped
 * them.
 *
 * So the question is put to [TelecomManager.isInManagedCall] instead, which counts only calls from a
 * **managed** ConnectionService — the carrier's. Wi-Fi calling and VoLTE are carrier IMS and remain
 * managed, so the case this gate was built for is still covered. An app call is self-managed and is
 * no longer mistaken for a phone call.
 */
object VoipTelephonyGate {

    private const val TAG = "CV:VoipGate"

    /**
     * Whether an app-call capture may START.
     *
     * A managed call blocks a start from dialing through holding — ringing included, deliberately:
     * the audio mode moves around while a call is being set up, and a start that slips through in
     * that window produces exactly the recording this guard exists to prevent.
     */
    fun mayStart(managedCallInProgress: Boolean): Boolean = !managedCallInProgress

    /**
     * Whether an app-call capture already running must STOP.
     *
     * Both conditions are required, and each rules out a different mistake:
     *  - **a managed call**, so that an app call raising the device state to OFFHOOK cannot stop its
     *    own recording. That is the failure this gate caused on One UI.
     *  - **answered, not ringing**, so that an incoming call the user declines does not cost them the
     *    app call they were recording. If they do answer it, the OFFHOOK that follows stops the
     *    capture a moment later anyway.
     */
    fun mustStop(telephonyState: Int, managedCallInProgress: Boolean): Boolean =
        managedCallInProgress && telephonyState == TelephonyManager.CALL_STATE_OFFHOOK

    /**
     * Whether the carrier has a call up right now.
     *
     * Fails **open** — an unreadable Telecom reads as "no carrier call", matching
     * [com.baba.callvault.system.updates.CallInProgressGate]. A device that cannot answer the
     * question should lose the guard, not lose app-call recording altogether. Needs
     * `READ_PHONE_STATE`, which the app already holds for call detection.
     */
    fun managedCallInProgress(context: Context): Boolean = runCatching {
        context.getSystemService(TelecomManager::class.java)?.isInManagedCall ?: false
    }.onFailure {
        AppLogger.d(TAG, "Telecom unreadable (${it.message}) — treating as no carrier call")
    }.getOrDefault(false)

    /** Like [managedCallInProgress] but null when Telecom could not be asked, so a caller can tell "no" from "unknown". */
    fun managedCallOrNull(context: Context): Boolean? = runCatching {
        context.getSystemService(TelecomManager::class.java)?.isInManagedCall
    }.getOrNull()

    /** Convenience for the detector: may an app-call capture start on this device right now? */
    fun mayStartNow(context: Context): Boolean {
        val managed = managedCallInProgress(context)
        if (!managed) {
            // Logged only when the two signals disagree, which is the interesting case and the one
            // that used to be fatal: the device says a call is up, Telecom says it is not the
            // carrier's, so it is an app call and recording it is the whole point.
            val deviceState = deviceCallState(context)
            if (deviceState != TelephonyManager.CALL_STATE_IDLE) {
                AppLogger.i(
                    TAG,
                    "Device call state is $deviceState but no carrier call is up — app call, allowing capture"
                )
            }
        }
        return mayStart(managed)
    }

    /**
     * The device-wide call state, for the log line above only.
     *
     * Never a decision input any more. It cannot tell a carrier call from an app call, which is the
     * only thing this class needs to know.
     */
    private fun deviceCallState(context: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        context.getSystemService(TelephonyManager::class.java)?.callState
            ?: TelephonyManager.CALL_STATE_IDLE
    }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
}
