/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app does about a transcript whose recording may or may not still be there: whether the
 * reading view offers playback, and what its row in a list says.
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

    @Test
    fun a_transcript_with_no_audio_says_so() {
        assertEquals(
            TranscriptAudio.RowBadge.TextOnly,
            TranscriptAudio.badgeFor(hasAudio = false, isImported = false)
        )
    }

    @Test
    fun an_import_with_no_audio_says_text_only_rather_than_both() {
        // The maintainer's rule, and the right one: the trailing slot is a fixed width and the title
        // already ellipsises, so a second pill would be paid for out of the name. "Text only" wins
        // because it changes what the row can DO, where an origin is only history — and an import is
        // still visibly an import from the name the row is titled with.
        assertEquals(
            TranscriptAudio.RowBadge.TextOnly,
            TranscriptAudio.badgeFor(hasAudio = false, isImported = true)
        )
    }

    @Test
    fun an_import_that_still_has_its_audio_says_imported() {
        assertEquals(
            TranscriptAudio.RowBadge.Imported,
            TranscriptAudio.badgeFor(hasAudio = true, isImported = true)
        )
    }

    @Test
    fun an_ordinary_call_says_nothing() {
        // Most rows are these. A badge on every one of them would be a badge that means nothing.
        assertEquals(
            TranscriptAudio.RowBadge.None,
            TranscriptAudio.badgeFor(hasAudio = true, isImported = false)
        )
    }
}
