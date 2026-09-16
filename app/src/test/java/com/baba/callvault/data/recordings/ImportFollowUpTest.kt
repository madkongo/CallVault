/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.transcription.TranscriptionLengthLimit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order in which an imported file's excuses are checked.
 *
 * Every wrong order here renders perfectly and produces a different shade of nothing-happens, which
 * is why it is a function rather than a sequence of `if`s inside a composable: asking the language
 * for work that was never going to run, queueing a job for a model that is not there, or handing the
 * runner a file it decodes and then abandons.
 */
class ImportFollowUpTest {

    private val overTheLimitMs = (TranscriptionLengthLimit.MAX_MINUTES + 5L) * 60_000L
    private val underTheLimitMs = 3L * 60_000L

    @Test
    fun `an ordinary file with a model installed goes to the language question`() {
        assertEquals(
            ImportFollowUp.Step.AskLanguage,
            ImportFollowUp.after(underTheLimitMs, isModelInstalled = true),
        )
    }

    @Test
    fun `length is refused before anything else, model or no model`() {
        // Checked first on purpose: the other way round, someone answers a language question and
        // reads an estimate for a run the runner was always going to refuse.
        assertEquals(
            ImportFollowUp.Step.TooLong(TranscriptionLengthLimit.MAX_MINUTES + 5),
            ImportFollowUp.after(overTheLimitMs, isModelInstalled = true),
        )
        assertEquals(
            ImportFollowUp.Step.TooLong(TranscriptionLengthLimit.MAX_MINUTES + 5),
            ImportFollowUp.after(overTheLimitMs, isModelInstalled = false),
        )
    }

    @Test
    fun `a missing model is said out loud rather than queued`() {
        // The worker retries indefinitely while its model is absent, which is right for a download
        // still in flight and wrong for a share: the file would appear to have been swallowed.
        assertEquals(
            ImportFollowUp.Step.NoModel,
            ImportFollowUp.after(underTheLimitMs, isModelInstalled = false),
        )
    }

    @Test
    fun `a file whose length the container will not say is still transcribed`() {
        // Deliberate, and the same rule the runner applies: a container declaring no duration is
        // ordinary, and refusing on "unknown" would silently drop short files with poor metadata.
        assertEquals(
            ImportFollowUp.Step.AskLanguage,
            ImportFollowUp.after(durationMs = 0L, isModelInstalled = true),
        )
    }

    @Test
    fun `the refusal quotes the file's own length, not only the limit`() {
        val step = ImportFollowUp.after(90L * 60_000L, isModelInstalled = true)
        assertEquals(ImportFollowUp.Step.TooLong(90), step)
    }
}
