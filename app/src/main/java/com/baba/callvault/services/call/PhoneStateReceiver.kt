/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.baba.callvault.calls.CallDetection
import com.baba.callvault.calls.CallDirection
import com.baba.callvault.calls.CallEvent
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.dialer.DialerRoleController
import com.baba.callvault.utils.AppLogger

/**
 * PhoneStateReceiver is a [BroadcastReceiver] that listens for phone call state changes INTENTS.
 * It extracts the relevant extras from the intent and forwards them to [CallSessionManager], which owns the recording decision logic.
 *
 * It is registered in AndroidManifest.xml to receive [TelephonyManager.ACTION_PHONE_STATE_CHANGED]
 * broadcasts. Android delivers this broadcast whenever a call starts ringing, is answered, or ends.
 *
 * **Note on "Double Broadcast" Behavior**:
 * As stated in [TelephonyManager.ACTION_PHONE_STATE_CHANGED] KDoc, the system sends two broadcasts for a single state transition:
 * 1. The first one always happen, and it has a null phone number.
 * 2. A second broadcast is received if the app has the READ_CALL_LOG permission, but this time containing the phone number.
 *
 * **Note on Phone Number Retrieval**:
 * Currently, we "cheats" and use the deprecated [TelephonyManager.EXTRA_INCOMING_NUMBER] to get the phone number (valid in the second broadcast!). If google ever fully discontinues this extra, we will need to
 * do call log polling (meaning whe will need to wait for the call to end, then query the latest phone number from the call log). This is not ideal, prone to race conditions. The other, preferred alternative
 * would be to use a privileged shell and call a hidden api to get that data, but I'm unsure now, will make an issue.
 * For every new Android releases, we must ensure they still send the EXTRA here:
 * Android 16: https://cs.android.com/android/platform/superproject/+/android-16.0.0_r4:frameworks/base/services/core/java/com/android/server/TelephonyRegistry.java;l=4349-4351
 * Android 11: https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/services/core/java/com/android/server/TelephonyRegistry.java;l=2566-2568
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CV:PhoneStateReceiver"
    }

    /**
     * Called by the Android framework when a phone-state change broadcast is received.
     *
     * @param context The [Context] in which the receiver is running.
     * @param intent  The [Intent] being received; must carry action
     *                [TelephonyManager.ACTION_PHONE_STATE_CHANGED].
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        // EXTRA_STATE is one of "IDLE", "RINGING", or "OFFHOOK".
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: throw IllegalArgumentException("Missing EXTRA_STATE in phone state change intent. How is this possible??")

        // EXTRA_INCOMING_NUMBER is set for both incoming and outgoing calls (I know the naming is confusing), but there is a nuance.
        // See this class KDoc comment "Double Broadcast" for more information on this. The first broadcast is always null.
        // NOTE: According to the Android source code, in case of "Double Broadcast", if the incoming number is anonymous, it should be an empty string.
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        AppLogger.v(TAG, "Raw broadcast received: state=$state number=$number")

        // Dialer mode is *effective* only when the preference is on AND CallVault actually holds the
        // default-dialer role (so its InCallService is bound and receiving calls). Resync the router's
        // active source to reality on every call: this self-heals a lost/never-granted role back to
        // broadcast detection (and hands ownership to Telecom only while the role is truly held).
        // Without this, pref-on + role-not-held would leave the router stuck on TELECOM and silently
        // drop the broadcast while Telecom never fires → total detection blackout (no recording).
        val effectiveDialerMode =
            AppPreferences(context).isDialerModeEnabled() && DialerRoleController(context).isDefaultDialer()
        if (CallDetection.isInitialized) CallDetection.setMode(effectiveDialerMode)
        if (effectiveDialerMode) {
            AppLogger.v(TAG, "Dialer mode effective (role held); broadcast detection deferred to Telecom")
            return
        }

        // During the post-boot window, CallMonitorService holds a LIVE telephony listener and is the
        // authoritative call-state source. The PHONE_STATE broadcast can be delivered seconds late on a
        // freshly-booted system (after the call has already ended), which would otherwise spawn a stale
        // session for a call that's over. So while the live listener is active, defer to it.
        if (CallMonitorService.isListening) {
            AppLogger.v(TAG, "CallMonitorService active; deferring to the live listener (broadcast ignored)")
            return
        }

        // Map the raw telephony state string to a normalized CallEvent and route it through the
        // CallEventRouter. The router forwards only events from the currently active source
        // (BROADCAST in recording-only mode), ensuring mutual exclusion with the Telecom path.
        val phase = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallEvent.Phase.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallEvent.Phase.ACTIVE
            TelephonyManager.EXTRA_STATE_IDLE    -> CallEvent.Phase.ENDED
            else -> return  // unknown state; handlePhoneState would also ignore it
        }
        // Direction is only unambiguous at RINGING (always incoming). OFFHOOK/IDLE direction is
        // derived inside CallSessionManager from the session state sequence, so UNKNOWN is safe here.
        val direction = if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            CallDirection.INCOMING
        } else {
            CallDirection.UNKNOWN
        }
        val event = CallEvent(phase, number, direction, isEmergency = false)
        CallDetection.emitBroadcast(event)
    }
}
