/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the reading view offers playback at all.
 *
 * Small, and pinned anyway: it is the rule three separate controls follow — the transport, a tapped
 * line and a summary's citation chip — and the failure it prevents is invisible. Dead controls do
 * not crash, do not log, and look exactly like working ones until somebody presses them.
 */
class TranscriptAudioTest {

    @Test
    fun a_transcript_that_still_has_its_recording_offers_playback() {
        assertTrue(TranscriptAudio.playbackOffered(hasAudio = true))
    }

    @Test
    fun a_transcript_whose_audio_is_gone_offers_none() {
        // Every finished "Transcribe only" import, and any transcript whose recording was deleted:
        // the two are separate databases and the cascade is called by hand.
        assertFalse(TranscriptAudio.playbackOffered(hasAudio = false))
    }
}
