/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BabApps item 144: list items that are pieces of the JSON itself are dropped on parse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SummaryJsonFragmentTest {

    private fun parse(keyPoints: String) = CallSummary.parse(
        """{"intent":"x","summary":"A call.","keyPoints":[$keyPoints],"decisions":[],"actionItems":[],"keyFacts":[]}"""
    )

    @Test
    fun keyNameFragmentIsDropped() {
        val s = parse("\"decisions]: [\", \"Real point\"")
        assertNotNull(s)
        assertEquals(listOf("Real point"), s!!.keyPoints)
    }

    @Test
    fun punctuationOnlyIsDropped() {
        assertEquals(listOf("Real point"), parse("\"],\", \"Real point\", \"{ }\"")!!.keyPoints)
    }

    @Test
    fun otherKeyShapesAreDropped() {
        for (bad in listOf("\\\"actionItems\\\": [", "keyFacts\":", "[tags", "decisions:")) {
            assertEquals(bad, emptyList<String>(), parse("\"$bad\"")!!.keyPoints)
        }
    }

    @Test
    fun sentenceMentioningDecisionsStays() {
        val items = listOf("We made two decisions: A and B", "The summary was long", "Intent to buy: 3 units")
        val s = parse(items.joinToString(",") { "\"$it\"" })!!
        assertEquals(items, s.keyPoints)
    }

    @Test
    fun hebrewAndPersianItemsStay() {
        val items = listOf("הם סיכמו להיפגש ביום ראשון", "تصمیم گرفتند فردا ملاقات کنند: ساعت ۱۰")
        val s = parse(items.joinToString(",") { "\"$it\"" })!!
        assertEquals(items, s.keyPoints)
    }
}
