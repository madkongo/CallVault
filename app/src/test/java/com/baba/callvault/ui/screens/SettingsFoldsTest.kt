/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import com.baba.callvault.ui.screens.SettingsFolds.Switches
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFoldsTest {

    private val allOn = Switches(isPhoneOn = true, isAppOn = true, isAsking = true)

    @Test
    fun `with every switch on, the matches are what shows`() {
        val matches = setOf("AfterCallModeRow", "AfterCallUnansweredRow")
        assertEquals(matches, SettingsFolds.shownRows(matches, allOn))
    }

    @Test
    fun `a folded match shows as its switch, counted once`() {
        // "discard" with Keep chosen: the mode row and the folded "If I don't answer" → one row on screen.
        val matches = setOf("AfterCallModeRow", "AfterCallUnansweredRow")
        assertEquals(setOf("AfterCallModeRow"), SettingsFolds.shownRows(matches, allOn.copy(isAsking = false)))
    }

    @Test
    fun `record from answer with both call switches off brings both switches`() {
        val off = Switches(isPhoneOn = false, isAppOn = false, isAsking = true)
        assertEquals(
            setOf(SettingsFolds.PHONE_SWITCH, SettingsFolds.APP_SWITCH),
            SettingsFolds.shownRows(setOf("RecordFromAnswerRow"), off),
        )
        assertEquals(setOf("RecordFromAnswerRow"), SettingsFolds.shownRows(setOf("RecordFromAnswerRow"), off.copy(isAppOn = true)))
    }
}
