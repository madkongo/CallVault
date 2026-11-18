/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.IntentCompat
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger

/**
 * Which SIM a call is on while it is still in progress.
 *
 * [TelephonyManager.getCallState] is the state of every call on the device, so on a dual-SIM phone
 * it reports off-hook for both subscriptions during any call. The sources here are the ones that
 * name one SIM: the per-SIM phone-state broadcast, a callback registered per subscription, and
 * [TelephonyManager.getCallStateForSubscription].
 */
internal object ActiveCallSim {

    private const val TAG = "CV:RecordSim"

    /** Sent by the platform with `slot` and `subscription` when one SIM's call state changes. */
    const val ACTION_SUBSCRIPTION_PHONE_STATE = "android.intent.action.SUBSCRIPTION_PHONE_STATE"

    /**
     * The 1-based slot to trust, or null.
     *
     * [outgoingSlot] is the SIM the user picked while dialling. [broadcastSlot] is from the
     * phone-state intent. [registryDump] is `dumpsys telephony.registry`, used only when the
     * in-process sources cannot tell the SIMs apart.
     */
    fun identify(context: Context, broadcastSlot: Int?, outgoingSlot: Int?, registryDump: String?): Int? {
        val callback = SimCallCallbacks.busySlot()
        val perSub = perSubscription(context)
        val registry = RegistrySim.slotOf(registryDump)
        val chosen = outgoingSlot ?: broadcastSlot ?: callback ?: perSub ?: registry
        AppLogger.i(
            TAG,
            "SIM sources: outgoing=${outgoingSlot ?: "-"} broadcast=${broadcastSlot ?: "-"} " +
                "callback=${callback ?: "-"} perSub=${perSub ?: "-"} registry=${registry ?: "-"} " +
                "→ ${chosen ?: "unknown"}",
        )
        return chosen
    }

    /** Keys of [intent], with phone numbers left out. Written even when debug logging is off. */
    fun logExtras(action: String?, intent: Intent) {
        val extras = intent.extras
        if (extras == null || extras.isEmpty) {
            AppLogger.i(TAG, "intent ${action ?: "?"} extras: none")
            return
        }
        // Only SIM keys. Other extras can be the phone number, and this log is kept for sharing.
        val simKeys = extras.keySet().filter { key ->
            val n = key.lowercase()
            n.contains("slot") || n.contains("sub") || n.contains("sim") || n.contains("account")
        }.sorted()
        val body = if (simKeys.isEmpty()) {
            "${extras.size()} other keys, no SIM key"
        } else {
            simKeys.joinToString(" ") { key ->
                val value = extras.get(key)
                val shown = when (value) {
                    is Int, is Long -> value.toString()
                    else -> "set"
                }
                "$key=$shown"
            }
        }
        AppLogger.i(TAG, "intent ${action ?: "?"} extras: $body")
    }

    /** A 1-based slot from extras some call broadcasts carry, or null. */
    fun hintFrom(context: Context, intent: Intent): Int? {
        val phoneId = listOf("slot", "slotId", "slot_id", SubscriptionManager.EXTRA_SLOT_INDEX)
            .firstNotNullOfOrNull { key -> intent.getIntExtra(key, -1).takeIf { it >= 0 } }
        if (phoneId != null) return phoneId + 1
        val subId = listOf(
            "subscription",
            "sub_id",
            "subId",
            TelephonyManager.EXTRA_SUBSCRIPTION_ID,
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
        ).firstNotNullOfOrNull { key -> intent.getIntExtra(key, -1).takeIf { it >= 0 } } ?: return null
        return slotForSubId(context, subId)
    }

    /** The SIM of an outgoing call, from the dial intent, or null when the intent does not say. */
    fun outgoingSlot(context: Context, intent: Intent): Int? {
        hintFrom(context, intent)?.let { return it }
        val handle = IntentCompat.getParcelableExtra(
            intent,
            TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
            PhoneAccountHandle::class.java,
        ) ?: return null
        if (!PermissionChecks.hasPhoneStatePermission(context)) return null
        val subId = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.getSubscriptionId(handle)
        }.getOrNull()?.takeIf { it >= 0 } ?: return null
        return slotForSubId(context, subId)
    }

    @SuppressLint("MissingPermission")
    private fun slotForSubId(context: Context, subId: Int): Int? {
        if (!PermissionChecks.hasPhoneStatePermission(context)) return null
        val slot = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subId)?.simSlotIndex
        }.getOrNull()
        return slot?.takeIf { it >= 0 }?.plus(1)
    }

    /**
     * The 1-based slot whose own subscription is not idle, or null.
     *
     * Uses [TelephonyManager.getCallStateForSubscription]. [TelephonyManager.getCallState] is not
     * used: it asks Telecom for every call on the device, so both SIMs read as off-hook.
     */
    @SuppressLint("MissingPermission")
    private fun perSubscription(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (!PermissionChecks.hasPhoneStatePermission(context)) return null
        val subs = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        }.getOrNull().orEmpty()
        if (subs.size < 2) return null
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val busy = subs.mapNotNull { info ->
            val slot = info.simSlotIndex.takeIf { it >= 0 }?.plus(1) ?: return@mapNotNull null
            val state = runCatching {
                tm.createForSubscriptionId(info.subscriptionId).callStateForSubscription
            }.getOrNull()
            AppLogger.i(
                TAG,
                "per-sub id=${info.subscriptionId} slot=$slot state=${stateName(state)}",
            )
            if (state == null || state == TelephonyManager.CALL_STATE_IDLE) null else slot
        }
        return if (busy.size == 1) busy[0] else null
    }

    private fun stateName(state: Int?): String = when (state) {
        null -> "unreadable"
        TelephonyManager.CALL_STATE_IDLE -> "idle"
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "off-hook"
        else -> "state$state"
    }
}
