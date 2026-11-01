package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkifyUrlsTest {

    private fun urls(text: String) = findUrlRanges(text).map { text.substring(it) }

    @Test
    fun `finds the fork link and drops sentence punctuation`() {
        val text = "available at https://github.com/kitsumed/ShizuCallRecorder\n\nsee https://en.wikipedia.org/wiki/Telephone_call_recording_laws.\n"
        assertEquals(
            listOf(
                "https://github.com/kitsumed/ShizuCallRecorder",
                "https://en.wikipedia.org/wiki/Telephone_call_recording_laws",
            ),
            urls(text),
        )
    }

    @Test
    fun `text without links has none`() {
        assertEquals(emptyList<String>(), urls("No links here, only words."))
    }

    @Test
    fun `the disclaimer body links the upstream repository`() {
        val body = java.io.File("src/main/res/values/strings.xml").readText()
        val disclaimer = body.substringAfter("name=\"disclaimer_body\"").substringBefore("</string>")
            .replace("\\n", "\n") // Android turns the XML's \n escapes into line breaks
        assertTrue("https://github.com/kitsumed/ShizuCallRecorder" in urls(disclaimer))
        assertTrue("Copyright © 2026 Kitsumed (Med)" in disclaimer)
    }
}
