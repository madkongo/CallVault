/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import java.security.MessageDigest

/** What [KeptSwitchApprover.run] did. Crosses the binder by name inside [KeptSwitchResult]. */
enum class KeptSwitchOutcome {
    /** Not on Wi-Fi, or the shell could not name the network. Nothing done. */
    NO_WIFI_IDENTITY,

    /**
     * The app has had this access point approved before. Nothing done. Only decided this way when Android's own
     * trusted list could not be read ([ALREADY_TRUSTED_BY_ANDROID] otherwise).
     */
    ALREADY_APPROVED,

    /**
     * adbd is not listening on a tcp port (Shizuku's tcp mode). Then Wireless debugging may be adbd's only
     * transport, and switching it back off could stop adbd with Shizuku and the recorder (device-quirks Q01).
     * Only tcp mode was measured safe (OP9, 2026-11-19), so nothing is approved and nothing written.
     */
    NOT_TCP_MODE,

    /** `adb_wifi_enabled` could not be read, so its state could not be put back. Nothing done. */
    WIRELESS_DEBUGGING_UNREADABLE,

    /** Wireless debugging is on: Android ignores an approval then (`MSG_ADBWIFI_ALLOW`). Nothing done. */
    WIRELESS_DEBUGGING_ON,

    /**
     * Off at the first read, on at the read just before approving: someone (the user) switched it on in between.
     * Left on; nothing approved, nothing written, so CallVault never writes off over that.
     */
    WIRELESS_DEBUGGING_TURNED_ON_MEANWHILE,

    /** `allowWirelessDebugging` is missing or threw. Nothing written. */
    APPROVAL_FAILED,

    /** Approved, Android switched Wireless debugging on, and it was switched back off. */
    APPROVED_RESTORED_OFF,

    /**
     * Approved, but Wireless debugging was never seen on within the wait, so nothing was written: writing off on an
     * unconfirmed read could switch off a Wireless debugging the user had on (a phone that hides the setting).
     */
    APPROVED_RESTORE_NOT_CONFIRMED,

    /** Approved and seen on, but switching it back off failed: Wireless debugging is left on. */
    APPROVED_RESTORE_FAILED,

    /**
     * Android's own trusted list (`dumpsys adb`) already holds this access point, whoever put it there (the user's
     * own "Always allow" tap, or an earlier approval). Nothing done: approving again only adds a duplicate entry.
     */
    ALREADY_TRUSTED_BY_ANDROID,
    ;

    /** Whether Android now holds the access point in its trusted list, so the app need not ask again. */
    val trusted: Boolean
        get() = this == APPROVED_RESTORED_OFF || this == APPROVED_RESTORE_FAILED || this == ALREADY_TRUSTED_BY_ANDROID
}

/**
 * One run's answer. [bssidKey] identifies the access point without naming it ([BssidPrivacy]); [maskedBssid] is
 * the only form of it ever logged. [restoreMs] is how long Wireless debugging was on because of the approval.
 */
data class KeptSwitchResult(
    val outcome: KeptSwitchOutcome,
    val bssidKey: String = "",
    val maskedBssid: String = "",
    val restoreMs: Long = -1L,
) {
    fun encode(): String = listOf(outcome.name, bssidKey, maskedBssid, restoreMs.toString()).joinToString(SEP)

    companion object {
        private const val SEP = "|"
        private const val FIELDS = 4

        /** Null for anything that is not an answer of this shape (an older recorder answers null). */
        fun decode(encoded: String?): KeptSwitchResult? {
            val parts = encoded?.split(SEP) ?: return null
            if (parts.size != FIELDS) return null
            val outcome = KeptSwitchOutcome.entries.firstOrNull { it.name == parts[0] } ?: return null
            return KeptSwitchResult(outcome, parts[1], parts[2], parts[3].toLongOrNull() ?: -1L)
        }
    }
}

/** An access point's identity without its address: the app stores keys, logs carry masks. */
object BssidPrivacy {

    private const val KEY_HEX_CHARS = 16
    private const val SHOWN_OCTETS = 2

    /**
     * A stable, one-way key for [bssid] (salted SHA-256, first 64 bits), case-insensitive. [salt] is per install:
     * an unsalted hash of a 48-bit address with a known vendor prefix could be reversed by trying every suffix.
     */
    fun key(bssid: String, salt: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$salt:${bssid.lowercase()}".toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(KEY_HEX_CHARS)

    /** All but the last two octets hidden: `**:**:**:**:ee:ff`. */
    fun mask(bssid: String): String {
        val octets = bssid.lowercase().split(":")
        return octets.mapIndexed { i, o -> if (i < octets.size - SHOWN_OCTETS) "**" else o }.joinToString(":")
    }
}

/**
 * Approves the current access point for Wireless debugging (always-allow) and leaves the switch as it found it.
 * Runs in the shell-uid recorder (review N15, Shizuku mode).
 *
 * Android couples the two: `allowWirelessDebugging` adds the access point to the trusted list AND switches Wireless
 * debugging on, and does nothing while it is on (`AdbDebuggingManager` android14-release, `MSG_ADBWIFI_ALLOW`). So:
 * read the switch first (S0); only from off, approve; wait to see Android switch it on; then write it off again.
 * That write only undoes the approval's own side effect. Measured on the OP9 (2026-11-19): switching it on and off
 * leaves the Shizuku server and a Shizuku-hosted recorder running.
 */
class KeptSwitchApprover(
    private val identity: () -> WifiIdentity?,
    /**
     * The BSSIDs (lower case) on Android's own trusted list, or null when it cannot be read. When it can, it alone
     * decides "already approved": the app's keys are only the fallback, as they cannot see the user's own taps or
     * an entry the user removed.
     */
    private val trustedBssids: () -> Set<String>? = { null },
    private val readWirelessDebugging: () -> Int?,
    /** Whether adbd listens on a tcp port (Shizuku's tcp mode), so Wireless debugging is not its only transport. */
    private val adbdListensOnTcp: () -> Boolean,
    private val approve: (WifiIdentity, alwaysAllow: Boolean) -> NetworkApproval,
    private val writeWirelessDebuggingOff: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
) {

    /** @param salt the app's per-install salt for [BssidPrivacy.key]. */
    fun run(knownKeys: Set<String>, salt: String): KeptSwitchResult {
        val wifi = identity() ?: return KeptSwitchResult(KeptSwitchOutcome.NO_WIFI_IDENTITY)
        val key = BssidPrivacy.key(wifi.bssid, salt)
        val masked = BssidPrivacy.mask(wifi.bssid)
        fun result(outcome: KeptSwitchOutcome, ms: Long = -1L) = KeptSwitchResult(outcome, key, masked, ms)

        val trusted = trustedBssids()
        if (trusted != null) {
            if (wifi.bssid.lowercase() in trusted) return result(KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID)
        } else if (key in knownKeys) {
            return result(KeptSwitchOutcome.ALREADY_APPROVED)
        }
        // Before anything that could write: the switch-back is safe only while adbd has another transport.
        if (!adbdListensOnTcp()) return result(KeptSwitchOutcome.NOT_TCP_MODE)
        when (readWirelessDebugging()) {
            null -> return result(KeptSwitchOutcome.WIRELESS_DEBUGGING_UNREADABLE)
            1 -> return result(KeptSwitchOutcome.WIRELESS_DEBUGGING_ON)
        }
        // Read again right before approving: a switch-on after this point is the approval's (or so close to it
        // that it cannot be told apart). One before it is someone else's, and is left alone.
        if (readWirelessDebugging() != 0) return result(KeptSwitchOutcome.WIRELESS_DEBUGGING_TURNED_ON_MEANWHILE)
        if (approve(wifi, ALWAYS_ALLOW) != NetworkApproval.APPROVED) return result(KeptSwitchOutcome.APPROVAL_FAILED)

        val approvedAt = clock()
        if (!awaitOn(approvedAt)) return result(KeptSwitchOutcome.APPROVED_RESTORE_NOT_CONFIRMED, clock() - approvedAt)
        val restored = writeWirelessDebuggingOff()
        val ms = clock() - approvedAt
        return result(if (restored) KeptSwitchOutcome.APPROVED_RESTORED_OFF else KeptSwitchOutcome.APPROVED_RESTORE_FAILED, ms)
    }

    /** Polls until Android has switched Wireless debugging on after the approval, or the wait runs out. */
    private fun awaitOn(since: Long): Boolean {
        while (true) {
            if (readWirelessDebugging() == 1) return true
            if (clock() - since >= confirmTimeoutMs) return false
            sleep(POLL_MS)
        }
    }

    companion object {
        /** Always: the reboot this exists for comes after a once-only approval has expired. */
        const val ALWAYS_ALLOW = true

        /** Android's handler runs right after the binder call; seconds are generous. */
        const val CONFIRM_TIMEOUT_MS = 3_000L
        const val POLL_MS = 100L
    }
}
