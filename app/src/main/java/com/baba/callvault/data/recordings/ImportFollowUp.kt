/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

/**
 * What happens between a finished import and a queued transcription.
 *
 * Pure, and separate from the screen, because the ORDER is the decision and getting it wrong is
 * silent every time. Each wrong order produces a different shade of nothing-happens:
 *
 *  - skipping the model check queues a job the worker retries for ever while no model is installed,
 *    so the app appears to have swallowed the file;
 *  - asking the language before the model check makes someone answer a question about work that
 *    cannot start.
 *
 * Length is not a step: there is no length limit. A long file is transcribed like any other, and the
 * confirmation says how long that takes on the phone.
 *
 * It is the same order the in-app path uses, written down once so the two doors cannot drift.
 */
object ImportFollowUp {

    /** What to do next with a file that is now in the library. */
    sealed interface Step {

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

    /** The next step for an imported file, given whether a transcription model is installed. */
    fun after(isModelInstalled: Boolean): Step =
        if (isModelInstalled) Step.AskLanguage else Step.NoModel
}
