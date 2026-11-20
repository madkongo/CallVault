/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.json.JSONArray
import org.json.JSONObject
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

    /** Built through org.json so an item's own quotes and apostrophes need no hand-escaping. */
    private fun parseItems(items: List<String>) = CallSummary.parse(
        JSONObject()
            .put("intent", "x").put("summary", "A call.")
            .put("keyPoints", JSONArray(items))
            .put("decisions", JSONArray()).put("actionItems", JSONArray()).put("keyFacts", JSONArray())
            .toString()
    )!!.keyPoints

    /** Review C18: real items the 2.5.2 rule dropped because a key word sat next to a quote or bracket. */
    @Test
    fun realItemsWithKeyWordsBesideQuotesOrMarkersStay() {
        val items = listOf(
            "[1:30] Summary of the quote to be emailed by Friday",
            "[3:10] Decisions on the budget were postponed to next week",
            "[2:05] Intent to sign the lease in May",
            "Agreed to send the written summary [4:12]",
            "Both parties' decisions are final",
            "The customers' intent to renew is unclear",
            "Client wants the \"summary\" by Friday",
            "[0:12] 'Tags' on the boxes must be printed",
            "[1:30] Tags darauf wurde der Vertrag unterschrieben"
        )
        assertEquals(items, parseItems(items))
    }

    @Test
    fun hebrewAndRussianItemsWithQuotesAndKeyWordsStay() {
        val items = listOf(
            "[1:20] הלקוח ביקש את ה-\"summary\" עד יום שישי",
            "סוכם עם מנכ\"ל החברה על ה-tags [2:40] החדשים",
            "[0:45] Клиент просит прислать \"decisions\" до пятницы",
            "Обсудили «summary» и 'intent' [3:05] нового договора"
        )
        assertEquals(items, parseItems(items))
    }

    @Test
    fun realFragmentsAreStillDropped() {
        val fragments = listOf(
            "\"actionItems\": [",
            "decisions]: [",
            "[1:30] decisions]: [",
            "keyFacts\":",
            "summary\": \"",
            "[tags",
            "\"tags\"",
            "}, \"actionItems",
            "decisions:",
            "Real point\"], \"decisions",
            "Pay the deposit\"], \"keyFacts\": [\"Rent is 900"
        )
        for (bad in fragments) {
            assertEquals(bad, listOf("Real point kept"), parseItems(listOf(bad, "Real point kept")))
        }
    }

    @Test
    fun aLoneKeyWordWithoutStructureIsKept() {
        // A one-word tag or point such as "Summary" was kept before 2.5.2's rule and still is.
        assertEquals(listOf("Summary", "intent"), parseItems(listOf("Summary", "intent")))
    }

    @Test
    fun droppedFragmentsAreCounted() {
        val raw = JSONObject()
            .put("intent", "x").put("summary", "A call.")
            .put("keyPoints", JSONArray(listOf("decisions]: [", "Real point")))
            .put("decisions", JSONArray(listOf("],")))
            .put("actionItems", JSONArray()).put("keyFacts", JSONArray(listOf("[1:30] Summary of the quote")))
            .toString()
        assertEquals(2, CallSummary.droppedFragmentCount(raw))
        assertEquals(0, CallSummary.droppedFragmentCount("not json"))
    }
}
