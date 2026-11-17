package com.baba.callvault.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsePolicyTest {

    @Test
    fun emptyAndShortTextIsNotLong() {
        assertFalse(CollapsePolicy.isLong(""))
        assertFalse(CollapsePolicy.isLong("Call the landlord back"))
    }

    @Test
    fun textJustAtTheCharacterLimitIsNotLong() {
        assertFalse(CollapsePolicy.isLong("a".repeat(CollapsePolicy.LONG_CHARS)))
    }

    @Test
    fun textPastTheCharacterLimitIsLong() {
        assertTrue(CollapsePolicy.isLong("a".repeat(CollapsePolicy.LONG_CHARS + 1)))
    }

    @Test
    fun surroundingWhitespaceDoesNotCount() {
        assertFalse(CollapsePolicy.isLong("   " + "a".repeat(CollapsePolicy.LONG_CHARS) + "\n\n"))
    }

    @Test
    fun manyShortLinesAreLong() {
        val lines = List(CollapsePolicy.COLLAPSED_LINES + 1) { "x" }.joinToString("\n")
        assertTrue(CollapsePolicy.isLong(lines))
        val fits = List(CollapsePolicy.COLLAPSED_LINES) { "x" }.joinToString("\n")
        assertFalse(CollapsePolicy.isLong(fits))
    }
}
