/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

/**
 * All SIMs, or one SIM.
 *
 * One SIM is stored by a stable id, never by the name the user typed in system settings.
 * [SimId.Icc] is the card id when the platform tells us one. [SimId.Sub] is the subscription id.
 * [SimId.Slot] is only the fallback for a value saved before those ids were stored, or when the
 * platform gives us neither. A stored "none" (an option that was removed) is read as [All].
 */
sealed class SimChoice {
    data object All : SimChoice()
    data class Only(val id: SimId) : SimChoice()

    val key: String
        get() = when (this) {
            All -> ALL
            is Only -> id.key
        }

    /** For logs. An ICCID is not written out. */
    fun logKey(): String = when (this) {
        All -> ALL
        is Only -> when (val id = id) {
            is SimId.Icc -> "icc"
            is SimId.Sub -> "sub:${id.value}"
            is SimId.Slot -> "slot:${id.value}"
        }
    }

    companion object {
        const val ALL = "all"

        /** "none" is the removed manual-only choice. It reads as [All]. */
        fun fromKey(raw: String?): SimChoice {
            if (raw == null || raw == ALL || raw == "" || raw == "none") return All
            return SimId.fromKey(raw)?.let { Only(it) } ?: All
        }
    }
}

/** How a single SIM is remembered. */
sealed class SimId {
    data class Icc(val value: String) : SimId()
    data class Sub(val value: Int) : SimId()
    data class Slot(val value: Int) : SimId()

    val key: String
        get() = when (this) {
            is Icc -> "$ICC_PREFIX$value"
            is Sub -> "$SUB_PREFIX$value"
            is Slot -> "$SLOT_PREFIX$value"
        }

    fun matches(card: SimCard): Boolean = when (this) {
        is Icc -> card.iccId == value || card.accountId == value
        is Sub -> card.subId == value || card.accountId == value.toString()
        is Slot -> card.slot == value
    }

    companion object {
        private const val ICC_PREFIX = "icc:"
        private const val SUB_PREFIX = "sub:"
        private const val SLOT_PREFIX = "slot:"

        /** Null for "all", "none", blank, or nonsense. */
        fun fromKey(raw: String?): SimId? {
            if (raw.isNullOrBlank() || raw == SimChoice.ALL || raw == "none") return null
            when {
                raw.startsWith(ICC_PREFIX) -> raw.removePrefix(ICC_PREFIX).takeIf { it.isNotBlank() }?.let { return Icc(it) }
                raw.startsWith(SUB_PREFIX) -> raw.removePrefix(SUB_PREFIX).toIntOrNull()?.takeIf { it >= 0 }?.let { return Sub(it) }
                raw.startsWith(SLOT_PREFIX) -> raw.removePrefix(SLOT_PREFIX).toIntOrNull()?.takeIf { it > 0 }?.let { return Slot(it) }
            }
            // Older builds stored a bare 1-based slot.
            return raw.toIntOrNull()?.takeIf { it > 0 }?.let { Slot(it) }
        }
    }
}

/**
 * One SIM that is in the phone right now. [label] is display only.
 *
 * [accountId] is the telephony phone-account id (the call log's PHONE_ACCOUNT_ID): the ICCID on
 * older Android, the subscription id on Android 13+.
 */
data class SimCard(
    val label: String,
    val slot: Int,
    val subId: Int,
    val iccId: String?,
    val accountId: String?,
)

/** What a call's SIM is, relative to the SIM the user picked. */
enum class SimHit { CHOSEN, OTHER, UNKNOWN }

/**
 * A call we could identify, from the call log's phone-account id and/or its slot.
 * Either field may be null. Both null means the call's SIM is unknown.
 */
data class CallSim(val accountId: String?, val slot: Int?)

object SimMatch {

    /** The id to store for [card]: card id, else subscription id, else slot. */
    fun stableId(card: SimCard): SimId {
        val icc = card.iccId?.takeIf { it.isNotBlank() }
        if (icc != null) return SimId.Icc(icc)
        return SimId.Sub(card.subId)
    }

    /**
     * Replace a slot-only choice with [stableId] when that slot is in [installed].
     * The stored value is unchanged when the slot is not currently in the phone.
     */
    fun stabilize(choice: SimChoice, installed: List<SimCard>): SimChoice {
        val id = (choice as? SimChoice.Only)?.id as? SimId.Slot ?: return choice
        val card = installed.find { it.slot == id.value } ?: return choice
        return SimChoice.Only(stableId(card))
    }

    fun present(choice: SimChoice, installed: List<SimCard>): Boolean =
        choice is SimChoice.Only && installed.any { choice.id.matches(it) }

    /**
     * [call] null means the call log had no row yet. Only a call that positively matches a DIFFERENT installed SIM
     * is [SimHit.OTHER]; no row, a row with no account, or an account that matches no installed SIM is
     * [SimHit.UNKNOWN], so the call is transcribed.
     */
    fun afterCall(choice: SimChoice, call: CallSim?, installed: List<SimCard>): SimHit {
        val id = (choice as? SimChoice.Only)?.id ?: return SimHit.CHOSEN
        if (call == null || (call.accountId == null && call.slot == null)) return SimHit.UNKNOWN
        val card = installed.find { it.sameCall(call) } ?: return SimHit.UNKNOWN
        return if (id.matches(card)) SimHit.CHOSEN else SimHit.OTHER
    }

    /** Whether [call] names a SIM that is in the phone now. */
    fun recognised(call: CallSim, installed: List<SimCard>): Boolean = installed.any { it.sameCall(call) }

    private fun SimCard.sameCall(call: CallSim): Boolean {
        val account = call.accountId
        if (account != null) {
            return accountId == account || iccId == account || subId.toString() == account
        }
        return call.slot != null && slot == call.slot
    }
}
