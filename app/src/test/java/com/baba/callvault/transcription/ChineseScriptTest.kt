/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.summary.CallSummary
import com.baba.callvault.summary.SummaryPrompt
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simplified to Traditional with OpenCC's full tables, for Cantonese and for Mandarin on HK / TW phones (item 125). */
class ChineseScriptTest {

    private val hk = ChineseScript.Conversion(ChineseScript.Variant.HK)
    private val tw = ChineseScript.Conversion(ChineseScript.Variant.TW)

    private fun locale(tag: String) = Locale.forLanguageTag(tag)

    // ---- part 2: the full tables -----------------------------------------------------------------------

    @Test
    fun `rare characters the old hand table missed are converted`() {
        // 龋 蠕 谶 and 鲸 were not in the 250-line table.
        assertEquals("齲", ChineseScript.convert("龋", hk))
        assertEquals("鯨魚", ChineseScript.convert("鲸鱼", hk))
        assertEquals("讖", ChineseScript.convert("谶", tw))
    }

    @Test
    fun `a character with several forms follows the word`() {
        assertEquals("頭髮", ChineseScript.convert("头发", hk))
        assertEquals("發生", ChineseScript.convert("发生", hk))
        assertEquals("乾淨", ChineseScript.convert("干净", tw))
        assertEquals("幹部", ChineseScript.convert("干部", tw))
        assertEquals("下週", ChineseScript.convert("下周", hk))
    }

    @Test
    fun `Hong Kong and Taiwan differ where their standards differ`() {
        assertEquals("這裏", ChineseScript.convert("这里", hk))
        assertEquals("這裡", ChineseScript.convert("这里", tw))
        assertEquals("麵包", ChineseScript.convert("面包", hk))
        assertEquals("麵包", ChineseScript.convert("面包", tw))
        assertEquals("臺灣", ChineseScript.convert("台湾", tw))
        assertEquals("台灣", ChineseScript.convert("台湾", hk))
    }

    @Test
    fun `Taiwan keeps what was said, no word swaps`() {
        // s2twp would turn 软件 into 軟體; only the characters change.
        assertEquals("軟件", ChineseScript.convert("软件", tw))
    }

    @Test
    fun `Latin, digits, punctuation and Traditional text are untouched`() {
        val text = "個 meeting 改咗去聽日下晝 3 點，我哋飲茶 OK"
        assertEquals(text, ChineseScript.convert(text, hk))
        assertEquals(text, ChineseScript.convert(text, tw))
    }

    @Test
    fun `converting twice gives the same text`() {
        val once = ChineseScript.convert("我们开会，关系很好，头发和干净的面包，这里的台湾人", tw)
        assertEquals(once, ChineseScript.convert(once, tw))
        val onceHk = ChineseScript.convert("我们开会，关系很好，头发和干净的面包，这里的台湾人", hk)
        assertEquals(onceHk, ChineseScript.convert(onceHk, hk))
    }

    @Test
    fun `no conversion means the text comes back as it is`() {
        assertEquals("我们开会", ChineseScript.convert("我们开会", null))
    }

    @Test
    fun `every character in the generated table is one pair, and the data file is there`() {
        val lines = ChineseScript::class.java.classLoader!!.getResourceAsStream("zh_hant/zh_hant.tsv")!!
            .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(lines.size > 10_000)
        lines.forEach { line ->
            val cols = line.split('\t')
            assertEquals(line, 4, cols.size)
        }
        // Characters outside the Basic Multilingual Plane are skipped by the loader; the rest is nearly all of them.
        val single = lines.count { it.startsWith("c\t") && it.split('\t')[1].length == 1 }
        assertTrue("single-unit character rows: $single of ${lines.size}", single > 2_500)
    }

    // ---- Cantonese wording kept from item 117 ----------------------------------------------------------

    @Test
    fun `Cantonese keeps 係 and 甚麼 but Mandarin on a Hong Kong phone does not`() {
        val yue = ChineseScript.Conversion(ChineseScript.Variant.HK, cantonese = true)
        assertEquals("佢係邊個", ChineseScript.convert("佢系边个", yue))
        assertEquals("甚麼", ChineseScript.convert("什么", yue))
        assertEquals("關係", ChineseScript.convert("关系", yue))
        assertEquals("什麼", ChineseScript.convert("什么", hk))
        assertEquals("系統", ChineseScript.convert("系统", hk))
    }

    // ---- part 3: who gets Traditional -------------------------------------------------------------------

    @Test
    fun `Hong Kong, Macau and Traditional-script phones are converted, mainland ones never are`() {
        assertEquals(ChineseScript.Variant.HK, ChineseScript.variantFor(locale("zh-HK")))
        assertEquals(ChineseScript.Variant.HK, ChineseScript.variantFor(locale("zh-Hant-HK")))
        assertEquals(ChineseScript.Variant.HK, ChineseScript.variantFor(locale("zh-MO")))
        assertEquals(ChineseScript.Variant.TW, ChineseScript.variantFor(locale("zh-TW")))
        assertEquals(ChineseScript.Variant.TW, ChineseScript.variantFor(locale("zh-Hant")))
        assertNull(ChineseScript.variantFor(locale("zh-CN")))
        assertNull(ChineseScript.variantFor(locale("zh-Hans")))
        assertNull(ChineseScript.variantFor(locale("zh-SG")))
        assertNull(ChineseScript.variantFor(locale("zh")))
        assertNull(ChineseScript.variantFor(locale("en-US")))
        assertNull(ChineseScript.variantFor(locale("he")))
    }

    @Test
    fun `a pinned Chinese transcript follows the locale, Cantonese is always Hong Kong, nothing else changes`() {
        assertEquals(ChineseScript.Conversion(ChineseScript.Variant.HK), ChineseScript.forTranscript("zh", locale("zh-HK")))
        assertEquals(ChineseScript.Conversion(ChineseScript.Variant.TW), ChineseScript.forTranscript("zh", locale("zh-TW")))
        assertNull(ChineseScript.forTranscript("zh", locale("zh-CN")))
        assertNull(ChineseScript.forTranscript("zh", locale("en")))
        val yue = ChineseScript.forTranscript("yue", locale("en"))
        assertNotNull(yue)
        assertTrue(yue!!.cantonese)
        assertNull(ChineseScript.forTranscript("he", locale("zh-HK")))
        assertNull(ChineseScript.forTranscript(null, locale("zh-HK")))
    }

    @Test
    fun `a transcript segment keeps its timings and its parts are converted`() {
        val part = TranscriptSegment(1_000, 2_000, "我们")
        val out = ChineseScript.convert(TranscriptSegment(1_000, 4_000, "我们开会", parts = listOf(part)), tw)
        assertEquals("我們開會", out.text)
        assertEquals("我們", out.parts.single().text)
        assertEquals(4_000L, out.endMs)
    }

    // ---- summaries -------------------------------------------------------------------------------------

    @Test
    fun `a Chinese summary on a Hong Kong or Taiwan phone asks for Traditional and is converted`() {
        assertEquals("zh-HK", ChineseScript.summaryLanguage("zh", locale("zh-HK")))
        assertEquals("zh-TW", ChineseScript.summaryLanguage("zh", locale("zh-Hant")))
        assertEquals("zh", ChineseScript.summaryLanguage("zh", locale("zh-CN")))
        assertEquals("he", ChineseScript.summaryLanguage("he", locale("zh-HK")))
        assertTrue(SummaryPrompt.languageName("zh-HK")!!.contains("Traditional"))
        assertTrue(SummaryPrompt.languageName("zh-TW")!!.contains("Taiwan"))
        assertEquals(hk, ChineseScript.forSummary("zh-HK"))
        assertEquals(tw, ChineseScript.forSummary("zh-TW"))
        assertTrue(ChineseScript.forSummary("yue")!!.cantonese)
        assertNull(ChineseScript.forSummary("zh"))
        assertNull(ChineseScript.forSummary("en"))
    }

    @Test
    fun `every text of a summary is converted`() {
        val summary = CallSummary("关于会议", "我们开会", listOf("头发"), listOf("决定"), listOf("联系他"), listOf("三点"))
        val out = summary.mapText { ChineseScript.convert(it, hk) }
        assertEquals("關於會議", out.intent)
        assertEquals("我們開會", out.summary)
        assertEquals(listOf("頭髮"), out.keyPoints)
        assertEquals(listOf("決定"), out.decisions)
        assertEquals(listOf("聯繫他"), out.actionItems)
        assertEquals(listOf("三點"), out.keyFacts)
    }

    @Test
    fun `the app language decides only when it is Chinese, otherwise the phone does`() {
        val tw = Locale.forLanguageTag("zh-TW")
        // App set to English on a Taiwan phone: the phone decides, so Traditional.
        assertEquals(tw, ChineseScript.chooseLocale(Locale.ENGLISH, tw))
        // App on system default: the phone.
        assertEquals(tw, ChineseScript.chooseLocale(null, tw))
        // App set to Simplified Chinese on a Taiwan phone: the app wins, no conversion.
        val cn = Locale.forLanguageTag("zh-CN")
        assertEquals(cn, ChineseScript.chooseLocale(cn, tw))
        assertEquals(null, ChineseScript.forTranscript("zh", ChineseScript.chooseLocale(cn, tw)))
        // App set to Hong Kong Chinese on an English phone: the app wins.
        val hk = Locale.forLanguageTag("zh-HK")
        assertEquals(hk, ChineseScript.chooseLocale(hk, Locale.US))
    }
}
