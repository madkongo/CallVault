/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

private val URL_PATTERN = Regex("""https?://\S+""")

/** Sentence punctuation that ends a URL written in prose ("…/laws."). */
private const val TRAILING_PUNCTUATION = ".,;:!?)"

/** The http(s) URLs in [text] as index ranges, without trailing sentence punctuation. */
fun findUrlRanges(text: String): List<IntRange> =
    URL_PATTERN.findAll(text).map { match ->
        val url = match.value.trimEnd { it in TRAILING_PUNCTUATION }
        match.range.first until match.range.first + url.length
    }.filter { !it.isEmpty() }.toList()

/** [text] with every http(s) URL made tappable (opens in the browser) and underlined. */
fun linkifyUrls(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    findUrlRanges(text).forEach { range ->
        addLink(LinkAnnotation.Url(text.substring(range), styles), range.first, range.last + 1)
    }
}
