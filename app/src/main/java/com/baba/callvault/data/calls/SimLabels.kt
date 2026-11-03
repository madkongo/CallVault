/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.baba.callvault.system.permissions.PermissionChecks

/**
 * Which SIM a call used, as the label the user sees: the name they gave the SIM, else "SIM 1" / "SIM 2".
 *
 * Android does not tell an app whether a SIM's name was typed by the user (the name source is hidden API).
 * Measured on the OP9 (2026-11-03): an unnamed SIM reads displayName "SIM1", carrierName "Partner". So a
 * name counts as the user's unless it is the carrier's name or a default such as "SIM1", "SIM 2", "Card 1",
 * "eSIM". The maintainer's rule: the SIM's own name if it has one, else SIM 1/2.
 */
object SimLabels {

    private val DEFAULT_NAME = Regex("""^\s*(e?sim|card|slot|uim|sim card)\s*[-_#]?\s*\d*\s*$""", RegexOption.IGNORE_CASE)

    /** The label for a SIM in [slotIndex] (0-based), or null when nothing is known. */
    fun label(displayName: String?, carrierName: String?, slotIndex: Int?, simWord: String = "SIM"): String? {
        val name = displayName?.trim().orEmpty()
        if (name.isNotEmpty() && isUserName(name, carrierName)) return name.take(MAX_LEN)
        return slotIndex?.takeIf { it >= 0 }?.let { "$simWord ${it + 1}" }
    }

    /** True when [name] was most likely given by the user rather than Android or the carrier. */
    internal fun isUserName(name: String, carrierName: String?): Boolean =
        !DEFAULT_NAME.matches(name) && !name.equals(carrierName?.trim(), ignoreCase = true)

    private const val MAX_LEN = 24

    /** The SIM label and 1-based slot for one call-log account, cached for one listing pass. */
    data class Sim(val label: String, val slot: Int?)

    /** Resolves call-log accounts to SIMs. One instance per listing pass, so each account is looked up once. */
    class Resolver(private val context: Context, private val simWord: String) {
        private val cache = HashMap<Pair<String, String>, Sim?>()

        fun simFor(component: String?, accountId: String?): Sim? {
            if (component.isNullOrBlank() || accountId.isNullOrBlank()) return null
            return cache.getOrPut(component to accountId) { lookup(component, accountId) }
        }

        @SuppressLint("MissingPermission") // checked just below
        private fun lookup(component: String, accountId: String): Sim? = runCatching {
            if (!PermissionChecks.hasPhoneStatePermission(context)) return null
            val handle = PhoneAccountHandle(ComponentName.unflattenFromString(component) ?: return null, accountId)
            val subId = context.getSystemService(TelephonyManager::class.java)
                ?.getSubscriptionId(handle)?.takeIf { it >= 0 } ?: return null
            val info = context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subId) ?: return null
            val slot = info.simSlotIndex.takeIf { it >= 0 }
            label(info.displayName?.toString(), info.carrierName?.toString(), slot, simWord)
                ?.let { Sim(it, slot?.plus(1)) }
        }.getOrNull()
    }
}
