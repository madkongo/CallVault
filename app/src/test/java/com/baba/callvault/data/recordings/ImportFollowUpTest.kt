/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order in which an imported file's excuses are checked.
 *
 * Every wrong order here renders perfectly and produces a different shade of nothing-happens, which
 * is why it is a function rather than a sequence of `if`s inside a composable: asking the language
 * for work that was never going to run, or queueing a job for a model that is not there.
 */
class ImportFollowUpTest {

    @Test
    fun `a file with a model installed goes to the language question`() {
        assertEquals(ImportFollowUp.Step.AskLanguage, ImportFollowUp.after(isModelInstalled = true))
    }

    @Test
    fun `a missing model is said out loud rather than queued`() {
        // The worker retries indefinitely while its model is absent, which is right for a download
        // still in flight and wrong for a share: the file would appear to have been swallowed.
        assertEquals(ImportFollowUp.Step.NoModel, ImportFollowUp.after(isModelInstalled = false))
    }
}
