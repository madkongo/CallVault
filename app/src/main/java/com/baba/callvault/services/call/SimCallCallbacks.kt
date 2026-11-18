/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One [TelephonyCallback] per subscription.
 *
 * Registered on a [TelephonyManager] from [TelephonyManager.createForSubscriptionId], so the
 * callback is for that SIM. The default-subscription callback is not: the platform delivers the
 * device-wide state to it. A slot is reported only when exactly one subscription is not idle.
 */
internal object SimCallCallbacks {

    private const val TAG = "CV:RecordSim"

    private val executor = Executors.newSingleThreadExecutor()
    private val states = ConcurrentHashMap<Int, Int>()
    private val callbacks = ConcurrentHashMap<Int, TelephonyCallback>()
    private var listening = false

    /** The 1-based slot that is not idle, when exactly one subscription is. */
    fun busySlot(): Int? = singleBusy(states)

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!PermissionChecks.hasPhoneStatePermission(context)) return
        val app = context.applicationContext
        register(app)
        if (listening) return
        val subs = app.getSystemService(SubscriptionManager::class.java) ?: return
        runCatching {
            subs.addOnSubscriptionsChangedListener(
                executor,
                object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() = register(app)
                },
            )
            listening = true
        }.onFailure { AppLogger.w(TAG, "Could not watch SIM list: ${it.message}") }
    }

    fun singleBusy(states: Map<Int, Int>): Int? {
        val busy = states.filterValues { it != TelephonyManager.CALL_STATE_IDLE }.keys
        return if (busy.size == 1) busy.first() else null
    }

    private fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!PermissionChecks.hasPhoneStatePermission(context)) return
        synchronized(callbacks) {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val subs = context.getSystemService(SubscriptionManager::class.java) ?: return
        val infos = runCatching { subs.activeSubscriptionInfoList }.getOrNull().orEmpty()
        val live = infos.mapNotNull { info ->
            val slot = info.simSlotIndex.takeIf { it >= 0 }?.plus(1) ?: return@mapNotNull null
            info.subscriptionId to slot
        }.toMap()
        (callbacks.keys - live.keys).forEach { subId ->
            callbacks.remove(subId)?.let { callback ->
                runCatching { tm.createForSubscriptionId(subId).unregisterTelephonyCallback(callback) }
            }
        }
        states.keys.retainAll(live.values.toSet())
        live.forEach { (subId, slot) ->
            if (callbacks.containsKey(subId)) return@forEach
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    states[slot] = state
                    AppLogger.i(TAG, "callback slot=$slot subscription=$subId state=${stateName(state)}")
                }
            }
            runCatching {
                tm.createForSubscriptionId(subId).registerTelephonyCallback(executor, callback)
                callbacks[subId] = callback
            }.onFailure { AppLogger.w(TAG, "Could not listen on slot $slot: ${it.message}") }
        }
        }
    }

    private fun stateName(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> "idle"
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "off-hook"
        else -> "state$state"
    }
}
