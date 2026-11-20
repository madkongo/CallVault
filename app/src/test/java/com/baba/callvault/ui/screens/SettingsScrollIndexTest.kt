package com.baba.callvault.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings list is: search line (index 0), the mode row on some pages, then the groups. Jumping to a group
 * scrolled one item too high because the search line was not counted (review 2026-11-20).
 */
class SettingsScrollIndexTest {

    @Test
    fun `no group asked - top of the list`() {
        assertEquals(0, settingsScrollIndex(groupIndex = null, showsModeRow = true))
        assertEquals(0, settingsScrollIndex(groupIndex = null, showsModeRow = false))
    }

    @Test
    fun `first group on a page with the mode row sits after the search line and the mode row`() {
        assertEquals(2, settingsScrollIndex(groupIndex = 0, showsModeRow = true))
    }

    @Test
    fun `a group on a page without the mode row sits after the search line`() {
        assertEquals(1, settingsScrollIndex(groupIndex = 0, showsModeRow = false))
        assertEquals(4, settingsScrollIndex(groupIndex = 3, showsModeRow = false))
    }
}
