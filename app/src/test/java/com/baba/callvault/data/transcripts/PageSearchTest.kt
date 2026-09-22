/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of the word search on the Transcripts and Summaries pages: how what is typed
 * becomes an FTS expression, and how a hit's text becomes the excerpt shown under a row.
 */
class PageSearchTest {

    // ---- the MATCH expression ----

    @Test
    fun `each word is quoted so what is typed is words to find, not operators`() {
        assertEquals("\"one\" \"two\"", PageSearch.matchExpression("one two", prefixLast = false))
    }

    @Test
    fun `the last word matches as a prefix while it is still being typed`() {
        // Measured against sqlite's FTS4: a quoted prefix ("בדי"*) matches nothing; a bare one does.
        assertEquals("\"one\" tw*", PageSearch.matchExpression("one tw", prefixLast = true))
        assertEquals("בדי*", PageSearch.matchExpression("בדי", prefixLast = true))
    }

    @Test
    fun `a last word with anything but letters and digits in it is quoted, not prefixed`() {
        // Unquoted, a hyphen or a quote is an operator to FTS and a crash was once the result.
        assertEquals("\"one\" \"t-w\"", PageSearch.matchExpression("one t-w", prefixLast = true))
        assertEquals("\"say\" \"\"\"hi\"", PageSearch.matchExpression("say \"hi", prefixLast = true))
    }

    @Test
    fun `blank input is no expression at all`() {
        assertEquals("", PageSearch.matchExpression("   ", prefixLast = true))
    }

    // ---- the excerpt ----

    @Test
    fun `the excerpt is cut around the first word that matches, with the match marked`() {
        val text = "so I told him that the delivery would come on Tuesday, and he said fine, Tuesday it is"

        val excerpt = PageSearch.excerpt(text, "tuesday", radius = 12)!!

        assertEquals("…uld come on Tuesday, and he sai…", excerpt.text)
        assertEquals("Tuesday", excerpt.text.substring(excerpt.matchStart, excerpt.matchEnd))
    }

    @Test
    fun `a match at the very start or end is not decorated with an ellipsis it does not need`() {
        assertEquals("Tuesday it is", PageSearch.excerpt("Tuesday it is", "tues", radius = 20)!!.text)
        assertEquals("it is Tuesday", PageSearch.excerpt("it is Tuesday", "tues", radius = 20)!!.text)
    }

    @Test
    fun `hebrew matches as typed and is cut on the same rule`() {
        val excerpt = PageSearch.excerpt("אמרתי לו שהמשלוח יגיע ביום שלישי", "שלי", radius = 6)!!

        assertEquals("… ביום שלישי", excerpt.text)
        assertEquals("שלי", excerpt.text.substring(excerpt.matchStart, excerpt.matchEnd))
    }

    @Test
    fun `any of the typed words can be the one shown`() {
        // FTS matched the row on all of them; the excerpt shows whichever comes first in the text.
        val excerpt = PageSearch.excerpt("the cat sat on the mat", "mat cat", radius = 4)!!

        assertEquals("cat", excerpt.text.substring(excerpt.matchStart, excerpt.matchEnd))
    }

    @Test
    fun `a hit whose text does not contain the words is shown from its start rather than hidden`() {
        // FTS folds case and diacritics that a plain search does not; the row still matched.
        val excerpt = PageSearch.excerpt("résumé attached", "resume", radius = 5)!!

        assertEquals("résum…", excerpt.text)
        assertEquals(0, excerpt.matchEnd)
    }

    @Test
    fun `no text is no excerpt`() {
        assertNull(PageSearch.excerpt("   ", "word", radius = 5))
    }
}
