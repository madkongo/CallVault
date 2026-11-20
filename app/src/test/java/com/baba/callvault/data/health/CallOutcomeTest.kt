package com.baba.callvault.data.health

import org.junit.Assert.assertEquals
import org.junit.Test

class CallOutcomeTest {

    @Test
    fun `a recording with bytes and a heard far party is verified`() {
        assertEquals(CallOutcome.Verified, CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = true))
    }

    @Test
    fun `a carrier recording with bytes is verified when there is no far-party signal`() {
        assertEquals(CallOutcome.Verified, CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = null))
    }

    @Test
    fun `an empty recording is an empty-file failure`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.EMPTY_FILE),
            CallOutcomes.of(0L, daemonDied = false, farPartyHeard = true)
        )
    }

    @Test
    fun `a dead daemon outranks the empty file it caused`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.DAEMON_DIED),
            CallOutcomes.of(0L, daemonDied = true, farPartyHeard = null)
        )
    }

    @Test
    fun `bytes but an unheard far party is one-sided`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.ONE_SIDED),
            CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = false)
        )
    }

    @Test
    fun `a negative size is judged empty here, which is why a caller must never pass it`() {
        // SafHelper.fileSize() returns -1 for "unknown size", never for "empty" — that's 0. This
        // function has no way to tell the two apart, so it judges anything <= 0 as EMPTY_FILE. That is
        // exactly why VoipRecordingCoordinator.onCallEnded must special-case a negative size and skip
        // calling this at all, rather than let an unknowable size render as a false empty-file failure.
        assertEquals(
            CallOutcome.Failed(FailureReason.EMPTY_FILE),
            CallOutcomes.of(-1L, daemonDied = false, farPartyHeard = null)
        )
    }

    // ---- header-only files: non-zero, but not a recording ----
    //
    // Seven recordings of exactly 98 bytes appeared on the maintainer's phone in August 2026 and
    // every one of them was classified Verified, because 98 is greater than 0. 98 bytes is a bare
    // container header with no audio samples in it at all.
    //
    // This is the failure mode users punish hardest: ~86 sources in the 2026-08-27 research describe
    // blank recordings discovered days later, for court evidence and medical calls. Failing loudly
    // costs one call; reporting success on a file with no audio costs the call *and* the user's
    // confidence in every recording they have not yet checked.

    @Test
    fun `a header-only file is not a successful recording`() {
        // The exact size of the real incident.
        assertEquals(
            CallOutcome.Failed(FailureReason.NO_AUDIO),
            CallOutcomes.of(98L, daemonDied = false, farPartyHeard = null)
        )
    }

    @Test
    fun `a genuinely empty file still reports as empty rather than as no-audio`() {
        // Zero bytes and a header with no samples are different failures with different causes, and
        // the status card should not blur them.
        assertEquals(
            CallOutcome.Failed(FailureReason.EMPTY_FILE),
            CallOutcomes.of(0L, daemonDied = false, farPartyHeard = null)
        )
    }

    @Test
    fun `the smallest plausible real recording is still verified`() {
        // One second at the lowest bitrate CallVault offers (8 kbps) is about 1000 bytes of audio
        // plus a header. The floor must sit below that or it would reject real short calls.
        assertEquals(
            CallOutcome.Verified,
            CallOutcomes.of(1_024L, daemonDied = false, farPartyHeard = null)
        )
    }

    @Test
    fun `a lost daemon still outranks a header-only file it caused`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.DAEMON_DIED),
            CallOutcomes.of(98L, daemonDied = true, farPartyHeard = null)
        )
    }

    @Test
    fun `a one-sided call is judged only once there is real audio to judge`() {
        // No audio at all is a bigger problem than which side is on it, and reporting "only your
        // side was recorded" about a file with nothing in it would send the user to the wrong fix.
        assertEquals(
            CallOutcome.Failed(FailureReason.NO_AUDIO),
            CallOutcomes.of(98L, daemonDied = false, farPartyHeard = false)
        )
    }

    @Test
    fun `a full-length recording that was all silence is not verified (N28)`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.NO_SOUND),
            CallOutcomes.of(500_000L, daemonDied = false, farPartyHeard = null, heardAnything = false)
        )
    }

    @Test
    fun `an unmeasured carrier recording stays verified, silence is never assumed`() {
        assertEquals(CallOutcome.Verified, CallOutcomes.of(500_000L, daemonDied = false, farPartyHeard = null, heardAnything = null))
    }

    @Test
    fun `a measured recording that heard something is verified`() {
        assertEquals(CallOutcome.Verified, CallOutcomes.of(500_000L, daemonDied = false, farPartyHeard = null, heardAnything = true))
    }

    @Test
    fun `a dead daemon and a header-only file both outrank silence`() {
        assertEquals(CallOutcome.Failed(FailureReason.DAEMON_DIED), CallOutcomes.of(500_000L, daemonDied = true, farPartyHeard = null, heardAnything = false))
        assertEquals(CallOutcome.Failed(FailureReason.NO_AUDIO), CallOutcomes.of(98L, daemonDied = false, farPartyHeard = null, heardAnything = false))
    }
}
