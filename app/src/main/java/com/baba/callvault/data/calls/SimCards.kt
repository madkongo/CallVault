/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.baba.callvault.system.permissions.PermissionChecks

/** The SIMs currently in the phone, with the ids a choice is stored by. Names are labels only. */
object SimCards {

    @SuppressLint("MissingPermission")
    fun list(context: Context, simWord: String): List<SimCard> {
        if (!PermissionChecks.hasPhoneStatePermission(context)) return emptyList()
        val subs = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        }.getOrNull().orEmpty()
        val accounts = phoneAccounts(context)
        val cards = subs.mapNotNull { info -> toCard(info, simWord, accounts[info.subscriptionId]) }
        val iccShared = cards.mapNotNull { it.iccId }.let { ids -> ids.size >= 2 && ids.distinct().size == 1 }
        return if (iccShared) cards.map { it.copy(iccId = null) } else cards
    }

    @Suppress("DEPRECATION") // getIccId is the card id; blank when the platform redacts it
    private fun toCard(info: SubscriptionInfo, simWord: String, accountId: String?): SimCard? {
        val slotIndex = info.simSlotIndex.takeIf { it >= 0 } ?: return null
        val label = SimLabels.label(
            info.displayName?.toString(),
            info.carrierName?.toString(),
            slotIndex,
            simWord,
            SimLabels.nameSourceOf(info.toString()),
        ) ?: return null
        val icc = runCatching { info.iccId }.getOrNull()?.trim()?.takeIf { usableIcc(it) }
        return SimCard(
            label = label,
            slot = slotIndex + 1,
            subId = info.subscriptionId,
            iccId = icc,
            accountId = accountId?.takeIf { it.isNotBlank() },
        )
    }

    /** A card id we can tell from another card. Redacted and all-zero values are not. */
    internal fun usableIcc(raw: String): Boolean {
        if (raw.length < 4) return false
        if (raw.any { it == '*' || it == 'x' || it == 'X' }) return false
        return raw.any { it.isDigit() } && raw.any { it != '0' }
    }

    @SuppressLint("MissingPermission")
    private fun phoneAccounts(context: Context): Map<Int, String> {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyMap()
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return emptyMap()
        val handles = runCatching { telecom.callCapablePhoneAccounts }.getOrNull().orEmpty()
        return handles.mapNotNull { handle ->
            val subId = runCatching { tm.getSubscriptionId(handle) }.getOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val id = handle.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            subId to id
        }.toMap()
    }
}
