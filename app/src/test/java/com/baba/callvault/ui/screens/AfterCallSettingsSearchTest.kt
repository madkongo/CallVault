/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The maintainer asked that every new setting be findable in Settings search; these are the keep-or-delete ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallSettingsSearchTest {

    private val texts = SettingsSearch.textsFor(ApplicationProvider.getApplicationContext())

    private fun finds(query: String, row: String) =
        assertTrue("\"$query\" does not find $row", row in SettingsSearch.matchingRows(texts, query))

    @Test
    fun `when a call ends is found by what people type`() {
        finds("when a call ends", "AfterCallModeRow")
        finds("keep or delete", "AfterCallModeRow")
        finds("discard", "AfterCallModeRow")
        finds("after the call", "AfterCallModeRow")
    }

    @Test
    fun `how to ask is found by pop-up and notification`() {
        finds("pop-up", "AfterCallAskStyleRow")
        finds("popup", "AfterCallAskStyleRow")
        finds("how to ask", "AfterCallAskStyleRow")
        finds("notification", "AfterCallAskStyleRow")
    }

    @Test
    fun `if I don't answer is found by its words and by 24 hours`() {
        finds("24 hours", "AfterCallUnansweredRow")
        finds("don't answer", "AfterCallUnansweredRow")
        finds("starred", "AfterCallUnansweredRow")
    }
}
