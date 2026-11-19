/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger
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
     * Approved, but Wireless debugging was never seen on within the wait (the first wait and the late re-reads), so
     * nothing was written: writing off on an unconfirmed read could switch off a Wireless debugging the user had on
     * (a phone that hides the setting). Recorded as a leftover ([ApprovalLeftovers]): if Android switches it on later,
     * the next check of this access point switches it back off ([LEFTOVER_RESTORED_OFF]).
     */
    APPROVED_RESTORE_NOT_CONFIRMED,

    /** Approved and seen on, but switching it back off failed: Wireless debugging is left on. */
    APPROVED_RESTORE_FAILED,

    /**
     * Android's own trusted list (`dumpsys adb`) already holds this access point, whoever put it there (the user's
     * own "Always allow" tap, or an earlier approval). Nothing done: approving again only adds a duplicate entry.
     */
    ALREADY_TRUSTED_BY_ANDROID,

    /** Trusted, and Wireless debugging was on there because of an earlier approval of ours: switched back off. */
    LEFTOVER_RESTORED_OFF,

    /** Trusted, Wireless debugging on because of an earlier approval of ours, and switching it off failed. */
    LEFTOVER_RESTORE_FAILED,

    /** Trusted, and Wireless debugging is on, not left on by CallVault (as far as this recorder knows): left alone. */
    TRUSTED_WIRELESS_DEBUGGING_ON,

    /**
     * Trusted, Wireless debugging on, and an approval of ours there never saw it on, but longer ago than the leftover
     * window: it may be ours or the user's, so it is left alone, with a warning. [KeptSwitchResult.restoreMs] holds how
     * long ago that approval was.
     */
    LEFTOVER_EXPIRED_LEFT_ON,

    /** Approved, then an error stopped the switch-back; Wireless debugging read on and was switched off. */
    INTERRUPTED_RESTORED_OFF,

    /** Approved, then an error stopped the switch-back; Wireless debugging read on and could not be switched off. */
    INTERRUPTED_LEFT_ON,

    /** Approved, then an error stopped the switch-back; Wireless debugging read off. Kept as a leftover. */
    INTERRUPTED_STILL_OFF,

    /** Approved, then an error stopped the switch-back; Wireless debugging could not be read. Kept as a leftover. */
    INTERRUPTED_STATE_UNKNOWN,
    ;

    /** Whether Android now holds the access point in its trusted list, so the app need not ask again. */
    val trusted: Boolean
        get() = this in TRUSTED
}

private val TRUSTED = setOf(
    KeptSwitchOutcome.APPROVED_RESTORED_OFF,
    KeptSwitchOutcome.APPROVED_RESTORE_FAILED,
    KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID,
    KeptSwitchOutcome.LEFTOVER_RESTORED_OFF,
    KeptSwitchOutcome.LEFTOVER_RESTORE_FAILED,
    KeptSwitchOutcome.TRUSTED_WIRELESS_DEBUGGING_ON,
    KeptSwitchOutcome.LEFTOVER_EXPIRED_LEFT_ON,
    KeptSwitchOutcome.INTERRUPTED_RESTORED_OFF,
    KeptSwitchOutcome.INTERRUPTED_LEFT_ON,
)

/**
 * Access points this recorder approved from "off" without having switched Wireless debugging back off yet (review
 * N15), with when: Android may still switch it on for them in the moments after. Keys and times only; in memory, so a
 * recorder started again forgets them, and an "on" it then finds is treated as not its own. Thread-safe.
 *
 * A leftover counts only for [KeptSwitchApprover.LEFTOVER_WINDOW_MS] after its approval: later, an "on" may be the
 * user's own (to pair, say), and the owner rule is never to undo a switch CallVault did not set.
 */
class ApprovalLeftovers {

    /** What a look found: [ours] within the window, otherwise expired (and now forgotten); [approvedAt] either way. */
    data class Found(val ours: Boolean, val approvedAt: Long)

    private val approvedAt = mutableMapOf<String, Long>()

    @Synchronized fun add(key: String, atMs: Long) { approvedAt[key] = atMs }
    @Synchronized fun remove(key: String) { approvedAt -= key }

    /** The leftover for [key] at [nowMs], or null. An expired one is dropped as it is read; older entries go too. */
    @Synchronized
    fun find(key: String, nowMs: Long, windowMs: Long): Found? {
        val at = approvedAt[key]
        approvedAt.entries.removeAll { nowMs - it.value !in 0..windowMs }
        return at?.let { Found(ours = nowMs - it in 0..windowMs, approvedAt = it) }
    }
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
    /** After [confirmTimeoutMs], how long to keep re-reading (every [LATE_POLL_MS]) for a late switch-on. */
    private val lateWindowMs: Long = LATE_WINDOW_MS,
    private val leftovers: ApprovalLeftovers = ApprovalLeftovers(),
) {

    /** @param salt the app's per-install salt for [BssidPrivacy.key]. */
    fun run(knownKeys: Set<String>, salt: String): KeptSwitchResult {
        val wifi = identity() ?: return KeptSwitchResult(KeptSwitchOutcome.NO_WIFI_IDENTITY)
        val key = BssidPrivacy.key(wifi.bssid, salt)
        val masked = BssidPrivacy.mask(wifi.bssid)
        fun result(outcome: KeptSwitchOutcome, ms: Long = -1L) = KeptSwitchResult(outcome, key, masked, ms)
        val toResult: (KeptSwitchOutcome, Long) -> KeptSwitchResult = { outcome, ms -> result(outcome, ms) }

        val trusted = trustedBssids()
        if (trusted != null) {
            if (wifi.bssid.lowercase() in trusted) return alreadyTrusted(key, toResult)
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

        // Approved from off: until it is seen back off, any "on" here is this approval's.
        leftovers.add(key, clock())
        val approvedAt = clock()
        return try {
            switchBackOff(key, approvedAt, toResult)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error after approving, putting Wireless debugging back: ${e.javaClass.simpleName}: ${e.message}")
            guardedSwitchBackOff(key, approvedAt, toResult)
        }
    }

    /** Android already trusts the access point. Puts back only an "on" that an approval of ours left behind. */
    private fun alreadyTrusted(key: String, result: (KeptSwitchOutcome, Long) -> KeptSwitchResult): KeptSwitchResult {
        val wd = readWirelessDebugging()
        val now = clock()
        val leftover = leftovers.find(key, now, LEFTOVER_WINDOW_MS)
        if (leftover == null) {
            val outcome = if (wd == 1) KeptSwitchOutcome.TRUSTED_WIRELESS_DEBUGGING_ON else KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID
            return result(outcome, -1L)
        }
        if (!leftover.ours) {
            // Too long after our approval to tell from the user's own switch-on: never written.
            val outcome = if (wd == 1) KeptSwitchOutcome.LEFTOVER_EXPIRED_LEFT_ON else KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID
            return result(outcome, if (wd == 1) now - leftover.approvedAt else -1L)
        }
        return when (wd) {
            0 -> {
                leftovers.remove(key)
                result(KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID, -1L)
            }
            null -> result(KeptSwitchOutcome.WIRELESS_DEBUGGING_UNREADABLE, -1L)
            else -> {
                if (!adbdListensOnTcp()) return result(KeptSwitchOutcome.NOT_TCP_MODE, -1L)
                val restored = writeWirelessDebuggingOff()
                if (restored) leftovers.remove(key)
                result(if (restored) KeptSwitchOutcome.LEFTOVER_RESTORED_OFF else KeptSwitchOutcome.LEFTOVER_RESTORE_FAILED, -1L)
            }
        }
    }

    /** Waits to see Android switch it on (first wait, then late re-reads), then switches it back off. */
    private fun switchBackOff(key: String, approvedAt: Long, result: (KeptSwitchOutcome, Long) -> KeptSwitchResult): KeptSwitchResult {
        val seenOn = awaitOn(approvedAt, confirmTimeoutMs, POLL_MS) || awaitOn(approvedAt, confirmTimeoutMs + lateWindowMs, LATE_POLL_MS)
        if (!seenOn) return result(KeptSwitchOutcome.APPROVED_RESTORE_NOT_CONFIRMED, clock() - approvedAt)
        val restored = writeWirelessDebuggingOff()
        if (restored) leftovers.remove(key)
        val outcome = if (restored) KeptSwitchOutcome.APPROVED_RESTORED_OFF else KeptSwitchOutcome.APPROVED_RESTORE_FAILED
        return result(outcome, clock() - approvedAt)
    }

    /** After an error past the approval: one plain read, and the same switch-off when it reads on. Never throws. */
    private fun guardedSwitchBackOff(key: String, approvedAt: Long, result: (KeptSwitchOutcome, Long) -> KeptSwitchResult): KeptSwitchResult {
        val ms = runCatching { clock() - approvedAt }.getOrDefault(-1L)
        val outcome = when (runCatching { readWirelessDebugging() }.getOrNull()) {
            1 -> if (runCatching { writeWirelessDebuggingOff() }.getOrDefault(false)) {
                leftovers.remove(key)
                KeptSwitchOutcome.INTERRUPTED_RESTORED_OFF
            } else {
                KeptSwitchOutcome.INTERRUPTED_LEFT_ON
            }
            0 -> KeptSwitchOutcome.INTERRUPTED_STILL_OFF
            else -> KeptSwitchOutcome.INTERRUPTED_STATE_UNKNOWN
        }
        return result(outcome, ms)
    }

    /** Polls every [pollMs] until Android has switched Wireless debugging on, or [untilMs] after [since] has passed. */
    private fun awaitOn(since: Long, untilMs: Long, pollMs: Long): Boolean {
        while (true) {
            if (readWirelessDebugging() == 1) return true
            if (clock() - since >= untilMs) return false
            sleep(pollMs)
        }
    }

    companion object {
        /** Always: the reboot this exists for comes after a once-only approval has expired. */
        const val ALWAYS_ALLOW = true

        /** Android's handler runs right after the binder call; seconds are generous. */
        const val CONFIRM_TIMEOUT_MS = 3_000L
        const val POLL_MS = 100L

        /** Late re-reads after the first wait: a slow Android switch-on is still put back, within a bound. */
        const val LATE_WINDOW_MS = 5_000L
        const val LATE_POLL_MS = 500L

        /**
         * How long after our approval an "on" there still counts as its leftover: the first wait and the late re-reads,
         * plus a margin. Past it, an "on" may be the user's and is never switched off by CallVault.
         */
        const val LEFTOVER_WINDOW_MS = 30_000L

        private const val TAG = "CV:WdApproval"
    }
}
