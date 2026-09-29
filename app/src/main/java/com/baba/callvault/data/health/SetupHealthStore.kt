/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import android.content.Context
import androidx.core.content.edit

/** Why a call failed to produce a usable recording. Persisted by name; unknown names read as null. */
/**
 * Why a call failed to produce a usable recording.
 *
 * [EMPTY_FILE] and [NO_AUDIO] are deliberately separate. Zero bytes means nothing was ever written;
 * a header-only file means the recorder started, produced a container, and captured no samples. They
 * have different causes and the status card should not blur them.
 */
enum class FailureReason {
    EMPTY_FILE, NO_AUDIO, DAEMON_DIED, ONE_SIDED,

    /** The phone closed CallVault mid-call; the part recorded before that was saved (backlog #4). */
    CUT_OFF,
}

/**
 * Everything the status card knows about whether recording actually works. All timestamps are epoch
 * millis; 0 means "never".
 */
data class HealthFacts(
    val lastVerifiedAt: Long = 0L,
    val verifiedFingerprint: String? = null,
    val lastFailureAt: Long = 0L,
    val lastFailureReason: FailureReason? = null,
    val lastFailureLabel: String? = null,
    val lastGapAt: Long = 0L,
    val lastGapLabel: String? = null,
    /**
     * A call the user expected recorded started while [lastNotReadyPrerequisite] was missing — a
     * USER-OWNED, excused cause, persisted separately from [lastGapAt]/[lastGapLabel] so the two never
     * blur: this one names why the call could not have been recorded, the other is the unexplained
     * (daemon-died) case this whole feature exists to catch.
     */
    val lastNotReadyAt: Long = 0L,
    val lastNotReadyLabel: String? = null,
    val lastNotReadyPrerequisite: Prerequisite? = null,
    val sweepWatermark: Long = 0L,
    val observedCallEnds: List<Long> = emptyList(),
    /**
     * Epoch millis at the start of the span this install has, as far as it knows, been
     * CONTINUOUSLY able to record in — or 0 if never established. Not monotonic: a confirmed
     * not-ready observation restarts it forward, since that observation is positive evidence the
     * earlier span is no longer vouched for. See [SetupHealthStore.observationWindowStart].
     */
    val observationWindowStart: Long = 0L
)

/**
 * Persists what real calls proved. Its own preference file rather than more keys on AppPreferences,
 * which is already oversized (see the agreed split in docs/dev-notes/backlog.md).
 *
 * [observeCall] records that a call was seen AT ALL, whatever the outcome — it answers the sweep's
 * "did we observe this call", never "did it work". A call that failed loudly is still one the sweep
 * must not report as unseen.
 */
class SetupHealthStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): HealthFacts = HealthFacts(
        lastVerifiedAt = prefs.getLong(KEY_VERIFIED_AT, 0L),
        verifiedFingerprint = prefs.getString(KEY_VERIFIED_FINGERPRINT, null),
        lastFailureAt = prefs.getLong(KEY_FAILURE_AT, 0L),
        lastFailureReason = prefs.getString(KEY_FAILURE_REASON, null)?.let { name ->
            FailureReason.entries.firstOrNull { it.name == name }
        },
        lastFailureLabel = prefs.getString(KEY_FAILURE_LABEL, null),
        lastGapAt = prefs.getLong(KEY_GAP_AT, 0L),
        lastGapLabel = prefs.getString(KEY_GAP_LABEL, null),
        lastNotReadyAt = prefs.getLong(KEY_NOTREADY_AT, 0L),
        lastNotReadyLabel = prefs.getString(KEY_NOTREADY_LABEL, null),
        lastNotReadyPrerequisite = prefs.getString(KEY_NOTREADY_PREREQUISITE, null)?.let { name ->
            Prerequisite.entries.firstOrNull { it.name == name }
        },
        sweepWatermark = prefs.getLong(KEY_SWEEP_WATERMARK, 0L),
        observedCallEnds = readRing(),
        observationWindowStart = prefs.getLong(KEY_WINDOW_START, 0L)
    )

    /**
     * A call produced a usable recording: the setup is proven as of [atMillis], clearing any failure
     * AND any gap — a later call proving things work again is exactly what retires either warning.
     */
    fun recordVerified(atMillis: Long, fingerprint: String) = prefs.edit {
        putLong(KEY_VERIFIED_AT, atMillis)
        putString(KEY_VERIFIED_FINGERPRINT, fingerprint)
        remove(KEY_FAILURE_AT); remove(KEY_FAILURE_REASON); remove(KEY_FAILURE_LABEL)
        remove(KEY_GAP_AT); remove(KEY_GAP_LABEL)
        remove(KEY_NOTREADY_AT); remove(KEY_NOTREADY_LABEL); remove(KEY_NOTREADY_PREREQUISITE)
    }

    /** A call was handled but produced nothing usable. The last verification date is left intact. */
    fun recordFailure(atMillis: Long, reason: FailureReason, label: String?) = prefs.edit {
        putLong(KEY_FAILURE_AT, atMillis)
        putString(KEY_FAILURE_REASON, reason.name)
        putString(KEY_FAILURE_LABEL, label)
    }

    /**
     * A call cut off by the phone closing CallVault, whose recorded part has now been saved (backlog #4).
     *
     * The call was never observed — the app was dead when it ended — so the sweep reports it as "not
     * recorded". That is no longer true: part of it is. So the call is marked observed (the sweep will not
     * raise it again), its own not-recorded warning is cleared (matched on the call-log start the sweep
     * stored), and the card says it was cut off instead. Any other call's warning is left alone.
     */
    fun recordCutOff(atMillis: Long, callStartedAt: Long?, callEndedAt: Long?, label: String?) {
        if (callEndedAt != null) observeCall(callEndedAt)
        prefs.edit {
            if (callStartedAt != null && prefs.getLong(KEY_GAP_AT, 0L) == callStartedAt) {
                remove(KEY_GAP_AT); remove(KEY_GAP_LABEL)
            }
            putLong(KEY_FAILURE_AT, atMillis)
            putString(KEY_FAILURE_REASON, FailureReason.CUT_OFF.name)
            putString(KEY_FAILURE_LABEL, label)
        }
    }

    /** Calls [listener] when the card's facts change; see [CARD_KEYS]. Keep the returned listener to remove it. */
    fun registerCardListener(listener: () -> Unit): android.content.SharedPreferences.OnSharedPreferenceChangeListener {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in CARD_KEYS) listener() }
        prefs.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun unregisterCardListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    /**
     * Persists that a call CallVault never observed happened at [atMillis], surviving past the sweep
     * pass that found it. Without this, the gap lived only in that pass's transient result: the sweep's
     * own watermark advance would put the same entry below the watermark on the very next resume, so it
     * would never be found again and the warning would silently vanish. This is the only way the gap
     * outlives the sweep — it clears solely via [recordVerified].
     */
    fun recordGap(atMillis: Long, label: String?) = prefs.edit {
        putLong(KEY_GAP_AT, atMillis)
        putString(KEY_GAP_LABEL, label)
    }

    /**
     * A call the user expected recorded started at [atMillis] while [prerequisite] was missing — a
     * USER-OWNED, excused gap. Persisted under its OWN keys, never [KEY_GAP_AT]/[KEY_GAP_LABEL]: the
     * two must never blur into each other, since one excuses the call and the other is precisely the
     * silent daemon failure this feature exists to report. Cleared only by [recordVerified], exactly
     * like every other warning here — a later call proving things work again is what retires it.
     */
    fun recordMissedWhileNotReady(atMillis: Long, label: String?, prerequisite: Prerequisite) = prefs.edit {
        putLong(KEY_NOTREADY_AT, atMillis)
        putString(KEY_NOTREADY_LABEL, label)
        putString(KEY_NOTREADY_PREREQUISITE, prerequisite.name)
    }

    /**
     * Remembers that a call ended at [endedAtMillis], whatever came of it. Keeps the newest [RING_SIZE].
     * Synchronized: this reads the current ring before writing it back, and two concurrent callers
     * racing here could drop an entry — which reads as a false CallNotRecorded alarm rather than a
     * merely-missed one, exactly what this feature must never do.
     */
    @Synchronized
    fun observeCall(endedAtMillis: Long) {
        val ends = (listOf(endedAtMillis) + readRing()).distinct().sortedDescending().take(RING_SIZE)
        prefs.edit { putString(KEY_OBSERVED_ENDS, ends.joinToString(",")) }
    }

    fun setSweepWatermark(millis: Long) = prefs.edit { putLong(KEY_SWEEP_WATERMARK, millis) }

    /**
     * Returns the start of the span this install has, as far as it knows, been CONTINUOUSLY able
     * to record in — establishing or restarting it as needed:
     *  - not ready → stores and returns [nowMillis]. Observing not-ready is positive evidence that
     *    the span up to now is no longer covered, so the window restarts here. This deliberately
     *    means the value is NOT monotonic and CAN move backward-in-meaning (forward in time) even
     *    once set — unlike a simple "first ever ready" timestamp.
     *  - ready and nothing stored yet → stores and returns [nowMillis] (first-ever-ready).
     *  - ready and a value is already stored → returns it unchanged. This is what keeps the case the
     *    whole gap-detection fix exists for working: a user whose status is READY but whose daemon
     *    died at call time still has an OLD window start, so that unrecorded call stays caught.
     *
     * Synchronized for the same reason as [observeCall]: this is a read-then-maybe-write, and two
     * racing callers could otherwise commit two different window starts.
     */
    @Synchronized
    fun observationWindowStart(isReady: Boolean, nowMillis: Long): Long {
        if (!isReady) {
            prefs.edit { putLong(KEY_WINDOW_START, nowMillis) }
            return nowMillis
        }
        val existing = prefs.getLong(KEY_WINDOW_START, 0L)
        if (existing != 0L) return existing
        prefs.edit { putLong(KEY_WINDOW_START, nowMillis) }
        return nowMillis
    }

    private fun readRing(): List<Long> =
        prefs.getString(KEY_OBSERVED_ENDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.sortedDescending()
            .orEmpty()

    companion object {
        /**
         * How many of the newest observed call ends [observeCall] keeps; the sweep's eviction
         * horizon. Public so callers of [CallGapDetector.sweep] can pass it as `ringCapacity`.
         */
        const val RING_SIZE = 20

        private const val FILE_NAME = "cv_setup_health"
        private const val KEY_VERIFIED_AT = "last_verified_at"
        private const val KEY_VERIFIED_FINGERPRINT = "verified_fingerprint"
        private const val KEY_FAILURE_AT = "last_failure_at"
        private const val KEY_FAILURE_REASON = "last_failure_reason"
        private const val KEY_FAILURE_LABEL = "last_failure_label"
        private const val KEY_GAP_AT = "last_gap_at"
        private const val KEY_GAP_LABEL = "last_gap_label"
        private const val KEY_NOTREADY_AT = "last_notready_at"
        private const val KEY_NOTREADY_LABEL = "last_notready_label"
        private const val KEY_NOTREADY_PREREQUISITE = "last_notready_prerequisite"
        private const val KEY_SWEEP_WATERMARK = "sweep_watermark"
        private const val KEY_OBSERVED_ENDS = "observed_call_ends"

        /** The keys whose change alters what the card says (not the sweep's bookkeeping). */
        private val CARD_KEYS = setOf(KEY_VERIFIED_AT, KEY_FAILURE_AT, KEY_FAILURE_REASON, KEY_GAP_AT, KEY_NOTREADY_AT)
        private const val KEY_WINDOW_START = "observation_window_start"
    }
}
