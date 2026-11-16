/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinned Cantonese is stored in Hong Kong Traditional (BabApps item 117). */
class CantoneseScriptTest {

    @Test
    fun `the line whisper returned in Simplified comes out Traditional`() {
        // What whisper-cli -l yue gave on large-v3-turbo-q8_0 for a Cantonese test sentence.
        assertEquals(
            "今日天氣好好,我們一起去飲茶啦,哥密靖改左去明日下周三點",
            CantoneseScript.toTraditionalHk("今日天气好好,我们一起去饮茶啦,哥密靖改左去明日下周三点"),
        )
    }

    @Test
    fun `Latin letters, digits and text that is already Traditional are untouched`() {
        val text = "個 meeting 改咗去聽日下晝 3 點，我哋飲茶 OK"
        assertEquals(text, CantoneseScript.toTraditionalHk(text))
    }

    @Test
    fun `the Cantonese word for is stays 係 instead of 系`() {
        assertEquals("佢係邊個", CantoneseScript.toTraditionalHk("佢系边个"))
        assertEquals("係咪", CantoneseScript.toTraditionalHk("系咪"))
    }

    @Test
    fun `words where 系 really is 系 keep it`() {
        assertEquals("系統", CantoneseScript.toTraditionalHk("系统"))
        assertEquals("關係", CantoneseScript.toTraditionalHk("关系"))
    }

    @Test
    fun `a character with two Traditional forms follows the word it sits in`() {
        assertEquals("頭髮", CantoneseScript.toTraditionalHk("头发"))
        assertEquals("發生", CantoneseScript.toTraditionalHk("发生"))
        assertEquals("麵包", CantoneseScript.toTraditionalHk("面包"))
        assertEquals("乾淨", CantoneseScript.toTraditionalHk("干净"))
        assertEquals("這裏", CantoneseScript.toTraditionalHk("这里"))
    }

    @Test
    fun `it is deterministic and idempotent`() {
        val once = CantoneseScript.toTraditionalHk("我们开会，关系很好，什么时候去饮茶")
        assertEquals(once, CantoneseScript.toTraditionalHk(once))
        assertEquals(once, CantoneseScript.toTraditionalHk("我们开会，关系很好，什么时候去饮茶"))
    }

    @Test
    fun `a segment and the lines inside it are both converted, timings kept`() {
        val part = TranscriptSegment(1_000, 2_000, "我们")
        val segment = TranscriptSegment(1_000, 4_000, "我们开会", parts = listOf(part))
        val out = CantoneseScript.toTraditionalHk(segment)
        assertEquals("我們開會", out.text)
        assertEquals("我們", out.parts.single().text)
        assertEquals(1_000L, out.startMs)
        assertEquals(4_000L, out.endMs)
    }

    @Test
    fun `pinned Cantonese gets no initial prompt, it made the wording Mandarin`() {
        assertEquals(null, TranscriptionPrompt.build(contactName = null, language = "yue"))
        assertEquals("陳大文.", TranscriptionPrompt.build(contactName = "陳大文", language = "yue"))
    }

    @Test
    fun `天气 and 们 become 天氣 and 們 while the Cantonese wording is kept`() {
        val out = CantoneseScript.toTraditionalHk("今日天气好好,我们一起去饮茶啦,哥密靖改左去明日下周三点")
        assertTrue(out.contains("天氣"))
        assertTrue(out.contains("我們"))
        assertTrue(out.contains("改左"))
    }
}
