/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.transcription.TranscriptionLengthLimit

/**
 * What happens between a finished import and a queued transcription.
 *
 * Pure, and separate from the screen, because the ORDER is the decision and getting it wrong is
 * silent every time. Each wrong order produces a different shade of nothing-happens:
 *
 *  - asking the language first and refusing for length afterwards makes someone answer a question
 *    about work that was never going to run;
 *  - skipping the model check queues a job the worker retries for ever while no model is installed,
 *    so the app appears to have swallowed the file;
 *  - skipping the length check hands the runner a file it decodes into memory and then abandons,
 *    after a long wait, with nobody to tell.
 *
 * It is the same order the in-app path uses, written down once so the two doors cannot drift.
 */
object ImportFollowUp {

    /** What to do next with a file that is now in the library. */
    sealed interface Step {

        /**
         * Too long to decode. The audio is kept either way — a refusal to *read* a file is not a
         * reason to destroy it — and [minutes] is its real length, so the message can say how far
         * over the limit it is rather than only quoting the limit.
         */
        data class TooLong(val minutes: Int) : Step

        /** Nothing installed to read it with. The audio is kept; the user is told what to install. */
        data object NoModel : Step

        /**
         * Ask which language, then show the estimate, then queue.
         *
         * An import ALWAYS asks both, whatever the two "don't ask" settings say. Those settings were
         * turned off by someone who had seen the numbers for their own calls: minutes long, in the
         * language they speak. A shared file is nobody's call — a voice note from abroad, a lecture,
         * an interview — and transcribing an hour of Hebrew as English produces fluent nonsense, no
         * error, and a run that has to be done again.
         */
        data object AskLanguage : Step
    }

    /**
     * The next step for a file of [durationMs], given whether a transcription model is installed.
     *
     * @param durationMs the copy's own length. Zero means the container would not say, which is
     *   ordinary and deliberately passes the length check — refusing on "unknown" would silently
     *   drop short files whose metadata happens to be missing.
     */
    fun after(durationMs: Long, isModelInstalled: Boolean): Step = when {
        TranscriptionLengthLimit.isTooLong(durationMs) -> Step.TooLong((durationMs / MS_PER_MINUTE).toInt())
        !isModelInstalled -> Step.NoModel
        else -> Step.AskLanguage
    }

    private const val MS_PER_MINUTE = 60_000L
}
