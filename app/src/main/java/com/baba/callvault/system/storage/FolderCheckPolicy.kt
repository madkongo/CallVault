/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

/**
 * One look at the recording folder, as [RecordingFolderCheck] measured it.
 *
 * @param uri      the folder that was set when it was measured, or null when none is set.
 * @param evidence what was seen, for the log: which test failed and what the system said.
 * @param cloud    the folder is served by a cloud provider (Drive, OneDrive, Dropbox): offline it cannot answer, so
 *                 only a missing grant ever counts as gone (see [FolderCheckPolicy]).
 */
data class FolderReading(val uri: String?, val kind: Kind, val evidence: String, val cloud: Boolean = false) {
    enum class Kind {
        /** The provider says the folder exists and can be written. */
        OK,

        /** No folder is set: the onboarding's NO_FOLDER case, not this check's. */
        NOT_SET,

        /** The folder is not usable and CallVault no longer holds its write grant: definite at once. */
        GRANT_MISSING,

        /** The grant is held, the storage is mounted and unlocked, and the provider says the folder is gone. */
        MISSING,

        /**
         * Not usable, for a reason that may pass on its own: the user's storage is still locked after a reboot, the
         * folder's volume (an SD card) is not mounted yet, or the provider threw or did not answer in time.
         */
        TRANSIENT,
    }
}

/** What [FolderCheckPolicy] remembers between readings. Stored as plain `key=value` lines. */
data class FolderCheckState(
    val uri: String?,
    val kind: FolderReading.Kind,
    val measuredAt: Long,
    /** When the folder was first seen not usable in the current run of bad readings; 0 while it is usable. */
    val firstBadAt: Long,
    /** The folder is gone for sure: Home is red and calls are kept inside CallVault. */
    val confirmedUnusable: Boolean,
    /** The user was alerted about this loss; reset when the folder is usable again (once per loss). */
    val alerted: Boolean,
) {
    fun encode(): String = listOf(
        "v=1",
        "uri=${uri.orEmpty()}",
        "kind=${kind.name}",
        "measured=$measuredAt",
        "firstBad=$firstBadAt",
        "confirmed=$confirmedUnusable",
        "alerted=$alerted",
    ).joinToString("\n")

    companion object {
        fun decode(text: String): FolderCheckState? {
            val fields = text.lineSequence()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            if (fields["v"] != "1") return null
            return FolderCheckState(
                uri = fields["uri"]?.takeIf { it.isNotEmpty() },
                kind = fields["kind"]?.let { k -> FolderReading.Kind.entries.firstOrNull { it.name == k } } ?: return null,
                measuredAt = fields["measured"]?.toLongOrNull() ?: return null,
                firstBadAt = fields["firstBad"]?.toLongOrNull() ?: return null,
                confirmedUnusable = fields["confirmed"]?.toBooleanStrictOrNull() ?: return null,
                alerted = fields["alerted"]?.toBooleanStrictOrNull() ?: return null,
            )
        }
    }
}

/**
 * When a not-usable recording folder counts as gone (review C98, N5).
 *
 * A false "folder gone" sends a user to re-pick a folder that works, so only a definite answer counts:
 *  - a missing write grant: at once (nothing short of the user picking the folder again brings it back);
 *  - "the folder does not exist" with the grant held and the storage mounted and unlocked: on a second reading at
 *    least [CONFIRM_DELAY_MS] after the first, so one slow or restarting provider answer is not enough;
 *  - anything that may pass on its own (storage locked after a reboot, SD card not mounted yet, a provider that
 *    threw or timed out): only after it has lasted [TRANSIENT_GRACE_MS].
 *
 * A cloud folder (Drive and the like, see [SafHelper.isCloudFolder]) is offline on every flight and in every dead
 * zone, and its provider then fails or says "no such file": for it only a missing grant counts; "missing" and
 * transient readings never confirm. A run of bad readings with a gap of more than [MAX_CHECK_GAP_MS] between two
 * checks starts again: two readings hours apart do not prove hours of trouble. A reading older than the stored one
 * (two checks that overlapped) is dropped ([Step.stale]).
 *
 * Once confirmed, the user is alerted once for that loss ([Step.alert]); a later usable reading ends the loss.
 */
object FolderCheckPolicy {

    /** The gap between the two "missing" readings that confirm a folder is gone. */
    const val CONFIRM_DELAY_MS = 20_000L

    /** How long a transient state must last before it counts as a loss. */
    const val TRANSIENT_GRACE_MS = 10 * 60_000L

    /** A confirmed loss older than this is not trusted by [isUnusable]: something should have measured it since. */
    const val MAX_AGE_MS = 24 * 60 * 60_000L

    /** Longer than this between two checks, and a run of bad readings starts again. */
    const val MAX_CHECK_GAP_MS = 30 * 60_000L

    /**
     * @param recheckInMs when to look again to settle an unconfirmed bad reading, or null.
     * @param alert       raise the "folder gone" alert now (first confirmation of this loss).
     * @param cleared     a loss just ended: the folder is usable again.
     * @param stale       the reading was older than the stored one and was dropped; [state] is the stored one.
     */
    data class Step(
        val state: FolderCheckState,
        val recheckInMs: Long?,
        val alert: Boolean,
        val cleared: Boolean,
        val stale: Boolean = false,
    )

    /** @param now when the reading was STARTED, so an older reading that finishes late is recognised. */
    fun next(previous: FolderCheckState?, reading: FolderReading, now: Long): Step {
        if (previous != null && previous.measuredAt > now) {
            return Step(previous, recheckInMs = null, alert = false, cleared = false, stale = true)
        }
        // A reading of another folder (the user picked a new one) owes nothing to the old one's history.
        val prev = previous?.takeIf { it.uri == reading.uri }
        val wasLost = prev?.confirmedUnusable == true || prev?.alerted == true
        return when (reading.kind) {
            FolderReading.Kind.OK, FolderReading.Kind.NOT_SET -> Step(
                FolderCheckState(reading.uri, reading.kind, now, firstBadAt = 0L, confirmedUnusable = false, alerted = false),
                recheckInMs = null, alert = false, cleared = wasLost && reading.kind == FolderReading.Kind.OK,
            )
            FolderReading.Kind.GRANT_MISSING -> bad(prev, reading, now, confirmedNow = true, recheckInMs = null)
            FolderReading.Kind.MISSING, FolderReading.Kind.TRANSIENT -> if (reading.cloud) {
                // Offline Drive says "missing" as readily as "failed": neither confirms. Rechecked when due anyway.
                bad(prev, reading, now, confirmedNow = false, recheckInMs = null)
            } else if (reading.kind == FolderReading.Kind.MISSING) {
                val previousMissing = prev?.kind == FolderReading.Kind.MISSING || prev?.kind == FolderReading.Kind.GRANT_MISSING
                val secondReading = previousMissing && now - prev!!.measuredAt >= CONFIRM_DELAY_MS
                bad(prev, reading, now, confirmedNow = secondReading, recheckInMs = CONFIRM_DELAY_MS)
            } else {
                val since = runStart(prev, now)
                val waited = now - since
                bad(
                    prev, reading, now,
                    confirmedNow = waited >= TRANSIENT_GRACE_MS,
                    recheckInMs = (TRANSIENT_GRACE_MS - waited).coerceAtLeast(CONFIRM_DELAY_MS),
                )
            }
        }
    }

    private fun bad(prev: FolderCheckState?, reading: FolderReading, now: Long, confirmedNow: Boolean, recheckInMs: Long?): Step {
        val confirmed = confirmedNow || prev?.confirmedUnusable == true
        val alerted = prev?.alerted == true
        val state = FolderCheckState(
            uri = reading.uri,
            kind = reading.kind,
            measuredAt = now,
            firstBadAt = runStart(prev, now),
            confirmedUnusable = confirmed,
            alerted = alerted || confirmed,
        )
        return Step(state, recheckInMs = if (confirmed) null else recheckInMs, alert = confirmed && !alerted, cleared = false)
    }

    /** When the current run of bad readings began: carried over only from a recent bad reading. */
    private fun runStart(prev: FolderCheckState?, now: Long): Long =
        prev?.takeIf { now - it.measuredAt <= MAX_CHECK_GAP_MS }?.firstBadAt?.takeIf { it > 0L } ?: now

    /**
     * Whether readiness should say the folder that is set now ([currentUri]) is not usable: only a confirmed loss,
     * measured for that same folder, within [MAX_AGE_MS]. Anything else (never measured, another folder, too old)
     * says nothing, so a phone whose folder works is never marked "not ready" by a missing or stale measurement.
     */
    fun isUnusable(state: FolderCheckState?, currentUri: String?, now: Long): Boolean =
        state != null && currentUri != null && state.uri == currentUri && state.confirmedUnusable &&
            now - state.measuredAt in 0..MAX_AGE_MS
}

/**
 * At most [maxPerWindow] always-on lines per [windowMs], so a provider that flaps between answers cannot fill the
 * always-on file (it keeps a bounded size, and a flood would push out what matters). Not thread-safe on its own.
 */
class AlwaysOnRateLimit(private val maxPerWindow: Int, private val windowMs: Long) {
    private var windowStart = Long.MIN_VALUE
    private var used = 0
    private var dropped = 0

    /** Null: drop this line. Otherwise how many lines were dropped before it (to say so in it). */
    fun take(now: Long): Int? {
        if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMs) {
            windowStart = now
            used = 0
        }
        if (used >= maxPerWindow) {
            dropped++
            return null
        }
        used++
        return dropped.also { dropped = 0 }
    }
}
