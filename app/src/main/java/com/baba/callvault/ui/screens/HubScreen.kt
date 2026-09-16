/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.navigation.HomeSection
import com.baba.callvault.ui.theme.LocalCvBrand

/**
 * The hub: what the app opens on, and what back from a section returns to.
 *
 * The status card and the banners beside it are passed in as [statusCards] rather than built here.
 * They belong on this page — a "recording is broken" notification now routes to the hub precisely
 * because this is where the card that explains it lives — but what they say is the shell's business,
 * and this screen has no view model.
 *
 * Every colour is stated. M3's own defaults resolve to CoralDeep in this scheme, so a tonal
 * container or a "neutral" variant left to the default renders red in a teal app; see the comment on
 * the scroll-to-top button in HomeScreen for the same trap.
 *
 * @param listState Hoisted by the caller, like every other section's: the hub leaves composition
 *                  whenever a section is open, and scroll position remembered inside it would go too.
 */
@Composable
fun HubScreen(
    recordingsCount: Int,
    transcriptsCount: Int,
    summariesCount: Int,
    listState: LazyGridState,
    onOpenSection: (HomeSection) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCommunity: () -> Unit,
    onTuckCommunity: () -> Unit,
    communityTucked: Boolean,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    statusCards: @Composable () -> Unit,
) {
    val brand = LocalCvBrand.current

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.app_name),
        titleTrailing = titleTrailing,
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.home_open_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(HUB_COLUMNS),
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(HUB_COLUMNS) }) { statusCards() }

            item(span = { GridItemSpan(HUB_COLUMNS) }) {
                CvSectionHeader(text = stringResource(R.string.home_hub_library_title))
            }

            item {
                HubCard(
                    icon = Icons.Filled.GraphicEq,
                    accent = MaterialTheme.colorScheme.primary,
                    count = stringResource(R.string.home_recordings_count, recordingsCount),
                    title = stringResource(R.string.home_recordings_title),
                    body = stringResource(R.string.home_hub_recordings_body),
                    onClick = { onOpenSection(HomeSection.Recordings) },
                )
            }
            item {
                HubCard(
                    icon = Icons.AutoMirrored.Filled.Article,
                    accent = brand.info,
                    count = stringResource(R.string.home_transcripts_count, transcriptsCount),
                    title = stringResource(R.string.home_transcripts_title),
                    body = stringResource(R.string.home_hub_transcripts_body),
                    onClick = { onOpenSection(HomeSection.Transcripts) },
                )
            }
            item {
                HubCard(
                    icon = Icons.Filled.AutoAwesome,
                    accent = brand.success,
                    count = stringResource(R.string.home_summaries_count, summariesCount),
                    title = stringResource(R.string.home_summaries_title),
                    body = stringResource(R.string.home_hub_summaries_body),
                    onClick = { onOpenSection(HomeSection.Summaries) },
                )
            }
            // Last, and only while the user has not tucked it away: an invitation is worth one cell of a
            // screen they open every day, and worth none at all once they have taken it up. Long-press
            // moves it to the pill beside Support rather than hiding it, so it is still one tap away.
            if (!communityTucked) {
                item {
                    HubCard(
                        icon = Icons.AutoMirrored.Filled.Send,
                        accent = brand.accent,
                        count = stringResource(R.string.home_hub_community_eyebrow),
                        title = stringResource(R.string.home_hub_community_title),
                        body = stringResource(R.string.home_hub_community_body),
                        onClick = onOpenCommunity,
                        onLongClick = onTuckCommunity,
                    )
                }
            }
        }
    }
}

/** Two, and stated once: the span of the full-width items has to agree with the column count. */
private const val HUB_COLUMNS = 2

/**
 * One section, in the hero card's grammar: tinted surface, circular icon chip, uppercase eyebrow,
 * title, body.
 *
 * The count is the eyebrow rather than a badge in a corner. It is the one thing on the card that
 * changes, and putting it where the eye starts means a glance at the hub answers "is there anything
 * new in there?" without reading the card.
 */
@Composable
private fun HubCard(
    icon: ImageVector,
    accent: Color,
    count: String,
    title: String,
    body: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // Stated, not a tonal-container default: secondaryContainer is CoralDeep in this scheme.
    val tinted = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)

    CvCard(color = tinted, onClick = onClick, onLongClick = onLongClick, contentPadding = PaddingValues(16.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = count.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
