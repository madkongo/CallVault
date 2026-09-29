/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupHealthStoreTest {

    private lateinit var store: SetupHealthStore

    @Before
    fun setUp() {
        store = SetupHealthStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a fresh install has never been verified and has no failure`() {
        val facts = store.read()
        assertEquals(0L, facts.lastVerifiedAt)
        assertNull(facts.verifiedFingerprint)
        assertNull(facts.lastFailureReason)
        assertEquals(emptyList<Long>(), facts.observedCallEnds)
        assertEquals(0L, facts.observationWindowStart)
    }

    @Test
    fun `recording a verified call clears an earlier failure`() {
        store.recordFailure(1_000L, FailureReason.EMPTY_FILE, "Feroza")
        store.recordVerified(2_000L, "fp-1")

        val facts = store.read()
        assertEquals(2_000L, facts.lastVerifiedAt)
        assertEquals("fp-1", facts.verifiedFingerprint)
        assertNull(facts.lastFailureReason)
        assertNull(facts.lastFailureLabel)
        assertEquals(0L, facts.lastFailureAt)
    }

    @Test
    fun `recording a gap round-trips`() {
        store.recordGap(4_000L, "Feroza")

        val facts = store.read()
        assertEquals(4_000L, facts.lastGapAt)
        assertEquals("Feroza", facts.lastGapLabel)
    }

    @Test
    fun `recording a verified call clears a stored gap`() {
        // A later call proving things work again is exactly what should retire an earlier gap warning.
        store.recordGap(4_000L, "Feroza")
        store.recordVerified(5_000L, "fp-1")

        val facts = store.read()
        assertEquals(0L, facts.lastGapAt)
        assertNull(facts.lastGapLabel)
    }

    @Test
    fun `recording a missed-while-not-ready entry round-trips, separately from the generic gap`() {
        store.recordMissedWhileNotReady(4_000L, "Feroza", Prerequisite.DEVELOPER_OPTIONS)

        val facts = store.read()
        assertEquals(4_000L, facts.lastNotReadyAt)
        assertEquals("Feroza", facts.lastNotReadyLabel)
        assertEquals(Prerequisite.DEVELOPER_OPTIONS, facts.lastNotReadyPrerequisite)
        // Must never blur into the unrelated generic gap this store also tracks.
        assertEquals(0L, facts.lastGapAt)
        assertNull(facts.lastGapLabel)
    }

    @Test
    fun `recording a verified call clears a stored missed-while-not-ready entry`() {
        store.recordMissedWhileNotReady(4_000L, "Feroza", Prerequisite.RECORDING_FOLDER)
        store.recordVerified(5_000L, "fp-1")

        val facts = store.read()
        assertEquals(0L, facts.lastNotReadyAt)
        assertNull(facts.lastNotReadyLabel)
        assertNull(facts.lastNotReadyPrerequisite)
    }

    @Test
    fun `an unknown persisted prerequisite name reads back as no missed-while-not-ready entry`() {
        // Same forward-compatible pattern as FailureReason: a name from a future version must not crash
        // or be misread, it just reads as null.
        store.recordMissedWhileNotReady(4_000L, "Feroza", Prerequisite.ADB_PAIRING)
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("cv_setup_health", android.content.Context.MODE_PRIVATE)
            .edit().putString("last_notready_prerequisite", "FROM_A_FUTURE_VERSION").commit()

        assertNull(store.read().lastNotReadyPrerequisite)
    }

    @Test
    fun `recording a failure keeps the earlier verification date`() {
        store.recordVerified(2_000L, "fp-1")
        store.recordFailure(3_000L, FailureReason.DAEMON_DIED, null)

        val facts = store.read()
        assertEquals(2_000L, facts.lastVerifiedAt)
        assertEquals(3_000L, facts.lastFailureAt)
        assertEquals(FailureReason.DAEMON_DIED, facts.lastFailureReason)
    }

    @Test
    fun `the observed-call ring keeps the twenty newest ends, newest first`() {
        (1L..25L).forEach { store.observeCall(it * 100L) }

        val ends = store.read().observedCallEnds
        assertEquals(20, ends.size)
        assertEquals(2_500L, ends.first())
        assertEquals(600L, ends.last())
    }

    @Test
    fun `observing the same call end twice does not consume two ring slots`() {
        store.observeCall(500L)
        store.observeCall(500L)
        assertEquals(listOf(500L), store.read().observedCallEnds)
    }

    @Test
    fun `the sweep watermark round-trips`() {
        store.setSweepWatermark(9_999L)
        assertEquals(9_999L, store.read().sweepWatermark)
    }

    @Test
    fun `observationWindowStart stores now on the first ready call`() {
        val value = store.observationWindowStart(isReady = true, nowMillis = 12_345L)

        assertEquals(12_345L, value)
        assertEquals(12_345L, store.read().observationWindowStart)
    }

    @Test
    fun `observationWindowStart stores now when not ready and unset`() {
        // Not ready is itself an observation: it is positive evidence recording was not covered up
        // to this moment, so the window starts here rather than staying unset at 0.
        val value = store.observationWindowStart(isReady = false, nowMillis = 12_345L)

        assertEquals(12_345L, value)
        assertEquals(12_345L, store.read().observationWindowStart)
    }

    @Test
    fun `observationWindowStart does not move while ready with a value already stored`() {
        store.observationWindowStart(isReady = true, nowMillis = 1_000L)

        val second = store.observationWindowStart(isReady = true, nowMillis = 2_000L)

        assertEquals(1_000L, second)
        assertEquals(1_000L, store.read().observationWindowStart)
    }

    @Test
    fun `observationWindowStart restarts forward on a not-ready observation, even after it was set while ready`() {
        store.observationWindowStart(isReady = true, nowMillis = 1_000L)

        val restarted = store.observationWindowStart(isReady = false, nowMillis = 2_000L)
        val afterReadyAgain = store.observationWindowStart(isReady = true, nowMillis = 3_000L)

        assertEquals(2_000L, restarted)
        // Readiness returning must not move it back to the earlier, now-discarded start.
        assertEquals(2_000L, afterReadyAgain)
        assertEquals(2_000L, store.read().observationWindowStart)
    }

    @Test
    fun `a call inside a span discarded by a not-ready observation is not a gap once readiness returns`() {
        // Ready at 1_000 (window start = 1_000). Not-ready observed at 5_000 — e.g. the user reopens
        // the app while dev options are still off — restarts the window there, since that observation
        // is positive evidence the 1_000..5_000 span is no longer vouched for. A call at 2_000, inside
        // the now-discarded span, must not be judged once readiness returns at 9_000 (which must not
        // move the window back to 1_000).
        store.observationWindowStart(isReady = true, nowMillis = 1_000L)
        store.observationWindowStart(isReady = false, nowMillis = 5_000L)
        val windowStart = store.observationWindowStart(isReady = true, nowMillis = 9_000L)
        assertEquals(5_000L, windowStart)

        val call = CallLogEntry(startedAt = 2_000L, durationSeconds = 60, isIncoming = true, label = "Feroza")
        val result = CallGapDetector.sweep(
            entries = listOf(call),
            observedCallEnds = emptyList(),
            autoRecordIncoming = true,
            autoRecordOutgoing = true,
            watermark = 0L,
            ringCapacity = SetupHealthStore.RING_SIZE,
            windowStart = windowStart
        )

        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun `an unknown persisted failure reason reads back as no failure`() {
        store.recordFailure(1_000L, FailureReason.ONE_SIDED, "x")
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("cv_setup_health", android.content.Context.MODE_PRIVATE)
            .edit().putString("last_failure_reason", "FROM_A_FUTURE_VERSION").commit()

        assertNull(store.read().lastFailureReason)
    }

    @Test
    fun `a cut-off call replaces its own not-recorded warning with the cut-off one`() {
        // voarch: the sweep saw a call with no recording (the app had been killed mid-call); the rescue
        // then saved the part recorded before the cut. The card must say what really happened.
        store.recordGap(5_000L, "Anne")
        store.recordCutOff(atMillis = 9_000L, callStartedAt = 5_000L, callEndedAt = 8_000L, label = "Anne")
        val facts = store.read()
        assertEquals(0L, facts.lastGapAt)
        assertEquals(FailureReason.CUT_OFF, facts.lastFailureReason)
        assertTrue(facts.observedCallEnds.contains(8_000L))
    }

    @Test
    fun `a cut-off call leaves another call's not-recorded warning alone`() {
        store.recordGap(5_000L, "Anne")
        store.recordCutOff(atMillis = 9_000L, callStartedAt = 7_000L, callEndedAt = 8_000L, label = "Bob")
        assertEquals(5_000L, store.read().lastGapAt)
    }
}
