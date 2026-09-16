/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.data.transcripts.db.TranscriptState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the only copy of somebody's audio may be destroyed.
 *
 * There is exactly one right answer and two wrong ones, and both wrong ones are silent. Deleting too
 * early loses the audio AND the words, which is the outcome the whole feature is arranged to make
 * impossible; never deleting leaves a file the user explicitly said they did not want kept. So the
 * rule is a pure function and every branch of it is pinned here.
 */
class TranscribeOnlyAudioTest {

    private val transcribeOnly =
        ImportedRecording.nameFor(
            importedAtMillis = 1_789_567_810_000L,
            label = "voice note",
            extension = ".opus",
            kind = ImportedRecording.Kind.TRANSCRIBE_ONLY,
        )

    private val kept =
        ImportedRecording.nameFor(
            importedAtMillis = 1_789_567_810_000L,
            label = "voice note",
            extension = ".opus",
        )

    private val call = "20260916_101010.123+0300_in_5551234.ogg"

    // ---- the one case that deletes

    @Test
    fun `a transcribe-only file with a finished transcript and words in it may go`() {
        assertEquals(
            TranscribeOnlyAudio.Verdict.DELETE,
            TranscribeOnlyAudio.verdictFor(transcribeOnly, TranscriptState.DONE, segmentCount = 12),
        )
    }

    // ---- everything that keeps the audio

    @Test
    fun `a call is never touched, whatever its transcript says`() {
        assertEquals(
            TranscribeOnlyAudio.Verdict.KEEP_NOT_TRANSCRIBE_ONLY,
            TranscribeOnlyAudio.verdictFor(call, TranscriptState.DONE, segmentCount = 12),
        )
    }

    @Test
    fun `an import the user asked to keep is never touched`() {
        assertEquals(
            TranscribeOnlyAudio.Verdict.KEEP_NOT_TRANSCRIBE_ONLY,
            TranscribeOnlyAudio.verdictFor(kept, TranscriptState.DONE, segmentCount = 12),
        )
    }

    @Test
    fun `a stopped run leaves no transcript row, and the audio stays`() {
        // What a Stop produces: the runner removes the row so the recording is offered again. The
        // file has to still be there for that offer to mean anything.
        assertEquals(
            TranscribeOnlyAudio.Verdict.KEEP_NO_TRANSCRIPT,
            TranscribeOnlyAudio.verdictFor(transcribeOnly, state = null, segmentCount = 0),
        )
    }

    @Test
    fun `a run still queued, running or failed keeps its audio`() {
        listOf(TranscriptState.QUEUED, TranscriptState.RUNNING, TranscriptState.FAILED).forEach { state ->
            assertEquals(
                "state=$state",
                TranscribeOnlyAudio.Verdict.KEEP_NOT_DONE,
                TranscribeOnlyAudio.verdictFor(transcribeOnly, state, segmentCount = 0),
            )
        }
    }

    @Test
    fun `a failed run that somehow stored words still keeps its audio`() {
        // The state is what says the run finished; segments alone do not. A partial write must not
        // be able to buy a delete.
        assertEquals(
            TranscribeOnlyAudio.Verdict.KEEP_NOT_DONE,
            TranscribeOnlyAudio.verdictFor(transcribeOnly, TranscriptState.FAILED, segmentCount = 5),
        )
    }

    @Test
    fun `a finished transcript with no words is not worth a file for`() {
        assertEquals(
            TranscribeOnlyAudio.Verdict.KEEP_NO_WORDS,
            TranscribeOnlyAudio.verdictFor(transcribeOnly, TranscriptState.DONE, segmentCount = 0),
        )
    }
}
