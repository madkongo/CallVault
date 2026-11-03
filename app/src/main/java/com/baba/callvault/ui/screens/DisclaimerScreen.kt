/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvHero
import com.baba.callvault.ui.common.CvPrimaryButton
import com.baba.callvault.ui.common.linkifyUrls
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.theme.CallVaultTheme


/**
 * One-time disclaimer screen shown on first launch, redesigned on the "Signal" design system.
 *
 * A branded hero (teal app glyph + welcome) sits above the scrollable legal body in a styled
 * [CvCard], a clean acknowledgement checkbox, and a teal [CvPrimaryButton] for Continue. All gating
 * Two gates, both of which the reader controls: the text has to be scrolled to the end, and the
 * acknowledgement has to be ticked. There used to be a third — a countdown that held Continue
 * disabled for five seconds — and it is gone. A forced wait does not make anyone read; it makes them
 * wait, and it charged the time again on every reinstall and on every phone someone set up. The
 * notice earns its place on the screen; the timer earned nothing.
 *
 * @param onContinue Called when the user presses the enabled "Continue" button. The caller
 *                   ([AppNavigation]) then persists the acceptance flag and triggers a refresh
 *                   (recompose) so the router advances to the Permissions screen.
 * @param modifier   Optional size/position modifier forwarded to the root [Surface].
 */
@Composable
fun DisclaimerScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    // [rememberSaveable] allow for the values to survive configuration
    // changes (like when recompose is triggered by a screen rotation)
    var hasAccepted by rememberSaveable { mutableStateOf(false) }
    var hasScrolledToBottom by rememberSaveable { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // Scroll detection: canScrollForward is false either when the user has reached the bottom
    // OR when the content fits entirely on screen without scrolling.
    LaunchedEffect(scrollState.canScrollForward) {
        if (!scrollState.canScrollForward) {
            hasScrolledToBottom = true
        }
    }

    // Continue button — enabled once the notice has been read to the end and acknowledged.
    val canContinue = hasAccepted && hasScrolledToBottom

    // Surface ensures the Material 3 background colour fills the screen correctly.
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Edge to edge: keep the hero below the status bar, as on the permissions screen.
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Branded hero: teal app glyph + welcome title + one-line subtitle.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                CvHero(
                    title = stringResource(R.string.disclaimer_title),
                    subtitle = stringResource(R.string.disclaimer_ui_hero_subtitle),
                    modifier = Modifier.weight(1f)
                )
            }

            // The legal disclaimer body in a styled, scrollable card (muted, with subtle inner scroll).
            CvCard(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp)
            ) {
                CvSectionHeader(
                    text = stringResource(R.string.disclaimer_ui_body_header),
                    modifier = Modifier.padding(start = 18.dp, top = 16.dp)
                )
                SelectionContainer {
                    Text(
                        // URLs (the fork notice's upstream link, the Wikipedia page) open in the browser.
                        text = linkifyUrls(
                            stringResource(R.string.disclaimer_body),
                            MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineBreak = LineBreak.Paragraph
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Acknowledgement checkbox — only becomes interactive after the user scrolls down.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .toggleable(
                        value = hasAccepted,
                        onValueChange = { if (hasScrolledToBottom) hasAccepted = it },
                        role = Role.Checkbox,
                        enabled = hasScrolledToBottom
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Checkbox(
                    checked = hasAccepted,
                    onCheckedChange = null, // Handled by the Row's toggleable modifier above.
                    enabled = hasScrolledToBottom
                )
                Text(
                    text = if (hasScrolledToBottom) stringResource(R.string.disclaimer_checkbox_label)
                    else stringResource(R.string.disclaimer_ui_scroll_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    // Dim the label to signal it's not yet interactive.
                    color = if (hasScrolledToBottom) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Continue button — a read prompt until the notice has been scrolled through, then "Continue".
            CvPrimaryButton(
                text = when {
                    !hasScrolledToBottom -> stringResource(R.string.disclaimer_must_read)
                    else -> stringResource(R.string.general_continue)
                },
                onClick = onContinue,
                enabled = canContinue
            )
        }
    }
}


/**
 * Renders [fullText] with inline clickable hyperlinks.
 *
 * Each entry in [links] maps a keyword substring to a URL. When the keyword is found in
 * [fullText], it is styled and tapping it opens the URL via [LocalUriHandler].
 *
 * @param fullText The complete plain-text string to display.
 * @param links    A map of keyword → URL pairs (e.g. `"Wiki" to "https://…"`).
 * @param modifier Optional layout modifier for the [Text] composable.
 */
@Composable
fun HyperlinkText(
    fullText: String,
    links: Map<String, String>,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    // Build a LinkInteractionListener that opens the URL stored in the Clickable tag.
    val listener = LinkInteractionListener { link ->
        if (link is LinkAnnotation.Clickable) {
            uriHandler.openUri(link.tag)
        }
    }

    val annotatedText = buildAnnotatedString {
        append(fullText)

        links.forEach { (keyword, url) ->
            val startIndex = fullText.indexOf(keyword)
            if (startIndex != -1) {
                val endIndex = startIndex + keyword.length

                // addLink attaches a Clickable annotation over the keyword range.
                addLink(
                    clickable = LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                textDecoration = TextDecoration.Underline,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        ),
                        linkInteractionListener = listener
                    ),
                    start = startIndex,
                    end = endIndex
                )
            }
        }
    }

    Text(text = annotatedText, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

@Preview(showBackground = true)
@Composable
private fun DisclaimerScreenPreview() {
    CallVaultTheme {
        DisclaimerScreen(onContinue = {})
    }
}
