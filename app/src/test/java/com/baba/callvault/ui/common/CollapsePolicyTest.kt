package com.baba.callvault.ui.common

import com.baba.callvault.summary.CallSummary
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

    private fun summary(text: String, points: Int = 0, intent: String = "Booking") = CallSummary(
        intent = intent,
        summary = text,
        keyPoints = List(points) { "p$it" },
        decisions = emptyList(),
        actionItems = emptyList(),
        keyFacts = emptyList()
    )

    @Test
    fun shortSummaryIsNotLong() {
        assertFalse(CollapsePolicy.isLongSummary(summary("Agreed to meet on Monday.")))
        assertFalse(
            CollapsePolicy.isLongSummary(summary("Agreed to meet.", points = CollapsePolicy.SHORT_SUMMARY_ITEMS))
        )
    }

    @Test
    fun longProseOrManyPointsMakeASummaryLong() {
        assertTrue(CollapsePolicy.isLongSummary(summary("a".repeat(CollapsePolicy.LONG_CHARS + 1))))
        assertTrue(
            CollapsePolicy.isLongSummary(summary("Short.", points = CollapsePolicy.SHORT_SUMMARY_ITEMS + 1))
        )
    }

    @Test
    fun manyShortLinesAreLong() {
        val lines = List(CollapsePolicy.COLLAPSED_LINES + 1) { "x" }.joinToString("\n")
        assertTrue(CollapsePolicy.isLong(lines))
        val fits = List(CollapsePolicy.COLLAPSED_LINES) { "x" }.joinToString("\n")
        assertFalse(CollapsePolicy.isLong(fits))
    }
}
