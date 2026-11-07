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
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.RedirectedCall

/**
 * Hands the number of an outgoing call, as the user dialled it, to [CallSessionManager]: with a call redirection app
 * the PHONE_STATE broadcast carries a proxy number instead. See [RedirectedCall].
 *
 * Deprecated for apps that *redirect* calls, but still sent to holders of
 * [android.Manifest.permission.PROCESS_OUTGOING_CALLS] and exempt from the implicit broadcast limits:
 * https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
 */
class NewOutgoingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CV:NewOutgoingCallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_NEW_OUTGOING_CALL) return
        val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
        AppLogger.v(TAG, "Outgoing call dialled: number=$number")
        CallSessionManager.getInstance(context).handleNewOutgoingCall(number)
    }
}
