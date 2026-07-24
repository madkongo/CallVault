/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import com.baba.callvault.system.openWirelessDebugging
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.baba.callvault.ui.common.OfflineDialogMode
import com.baba.callvault.ui.common.OfflineRecordingDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.CvStatusPill
import com.baba.callvault.ui.common.CvTone
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.ui.viewmodels.HomeViewModel
import com.baba.callvault.ui.viewmodels.HomeViewModel.DirectionFilter
import com.baba.callvault.ui.viewmodels.HomeViewModel.SourceFilter
import com.baba.callvault.ui.viewmodels.RecordingPlaybackController
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The main Home screen shown once onboarding and the setup wizard are complete.
 *
 * Redesigned on the "Signal" design system. Renders:
 *  - a prominent HERO STATUS CARD reflecting the app's best-effort health (ADB / daemon / folder), and
 *  - the in-app RECORDINGS list with an inline [android.media.MediaPlayer]-backed player for the active row.
 *
 * The Settings affordance is preserved as a top-app-bar action.
 *
 * @param onOpenSettings Called when the user taps the Settings action; the router maps this to
 *                       manual navigation to [com.baba.callvault.ui.navigation.AppScreen.Settings].
 * @param modifier       Optional layout modifier.
 * @param viewModel      The Home "Brain"; defaults to a [viewModel]-scoped [HomeViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val playback by viewModel.playback.collectAsState()
    // Show the "What's new" note once after an update lands (driven by the same signal as the banner).
    var showWhatsNew by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.updatedToVersion) {
        if (uiState.updatedToVersion != null) showWhatsNew = true
    }

    // Refresh status + recordings whenever the user returns to the screen (e.g. after a new call).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While a track is playing, tick the player position so the slider tracks playback.
    LaunchedEffect(playback.phase) {
        while (playback.phase == RecordingPlaybackController.Phase.PLAYING) {
            viewModel.syncPlaybackPosition()
            delay(500)
        }
    }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.app_name),
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
        if (showWhatsNew) {
            WhatsNewDialog(
                onDismiss = {
                    showWhatsNew = false
                    viewModel.dismissUpdatedBanner()
                },
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                HeroStatusCard(
                    status = uiState.status,
                    onAction = if (uiState.status == HomeViewModel.HomeStatus.UPDATE_REGRANT_NEEDED) {
                        { context.openWirelessDebugging() }
                    } else {
                        null
                    },
                )
            }

            uiState.updatedToVersion?.let { version ->
                item {
                    UpdatedBannerCard(
                        version = version,
                        onDismiss = { viewModel.dismissUpdatedBanner() }
                    )
                }
            }

            uiState.availableUpdateTag?.let { tag ->
                item {
                    UpdateBannerCard(
                        tag = tag,
                        isInstalling = uiState.isUpdateInstalling,
                        progressPercent = uiState.updateProgressPercent,
                        onUpdate = { viewModel.installAvailableUpdate() }
                    )
                }
            }

            val recordings = uiState.filteredRecordings

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CvSectionHeader(text = stringResource(R.string.home_recordings_title))
                    Spacer(Modifier.weight(1f))
                    if (recordings.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.home_recordings_count, recordings.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
            }

            // Faceted, non-wrapping filter chips shown once there is anything to filter.
            if (uiState.recordings.isNotEmpty()) {
                item {
                    RecordingFilterBar(
                        sourceFilter = uiState.sourceFilter,
                        directionFilter = uiState.directionFilter,
                        contactFilter = uiState.contactFilter,
                        dateFilter = uiState.dateFilter,
                        availableContacts = uiState.availableContacts,
                        availableDates = uiState.availableDates,
                        onSourceFilterChange = { viewModel.setSourceFilter(it) },
                        onDirectionFilterChange = { viewModel.setDirectionFilter(it) },
                        onContactFilterChange = { viewModel.setContactFilter(it) },
                        onDateFilterChange = { viewModel.setDateFilter(it) }
                    )
                }
            }

            if (recordings.isEmpty()) {
                item { EmptyRecordings() }
            } else {
                items(recordings, key = { it.uri.toString() }) { item ->
                    RecordingRow(
                        item = item,
                        playback = playback,
                        deleting = item.uri in uiState.deletingUris,
                        onPlayUri = { uri -> viewModel.play(uri) },
                        onPause = { viewModel.pausePlayback() },
                        onResume = { viewModel.resumePlayback() },
                        onSeek = { viewModel.seekTo(it) },
                        onDeleteAll = { viewModel.deleteRecording(item) },
                        onDeleteUri = { uri -> viewModel.deleteUri(uri) }
                    )
                }
            }
        }
    }
}

/**
 * Post-update "What's new" note — plain-language highlights of the release, with a one-tap opt-in to
 * enable off-Wi-Fi (loopback) recording behind the same security warning as the Settings toggle.
 * [onDismiss] closes the note AND clears the updated-banner so it doesn't reappear.
 */
@Composable
private fun WhatsNewDialog(onDismiss: () -> Unit) {
    var showOfflineDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_whatsnew_title)) },
        text = { Text(stringResource(R.string.home_whatsnew_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { showOfflineDialog = true }) {
                Text(stringResource(R.string.home_whatsnew_offline_cta))
            }
        },
    )
    if (showOfflineDialog) {
        // Shared enable flow: security warning → live spinner → "it's on" (auto-closes). The What's New
        // note stays open behind it, so the user lands back on it and taps OK to dismiss.
        OfflineRecordingDialog(
            mode = OfflineDialogMode.ENABLE,
            onResult = { },
            onClose = { showOfflineDialog = false },
        )
    }
}

/**
 * Dismissable confirmation shown once after an update lands ("CallVault updated to X.Y.Z"). Uses a
 * success (primary) tint with a check, and a close button that clears it for good.
 */
@Composable
private fun UpdatedBannerCard(version: String, onDismiss: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val tinted = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
    CvCard(color = tinted, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.home_updated_banner_text, version),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.general_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Slim banner shown under the hero card when a newer release is known. Tapping Update downloads,
 * verifies, and installs it (system confirm dialog may appear); while working the action shows a
 * small progress spinner. Auto-update users normally never see this — it appears only when the
 * silent path couldn't run (e.g. metered network or the shell being unavailable).
 */
@Composable
private fun UpdateBannerCard(
    tag: String,
    isInstalling: Boolean,
    progressPercent: Int,
    onUpdate: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val tinted = accent.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface)
    // While installing: a subtitle tracks the phase. Downloading (percent -1..99, -1 = not yet
    // reported) shows the download label; only once the download completes (percent >= 100) does it
    // switch to "Installing…". A determinate bar during download, indeterminate before/after.
    val subtitle = when {
        !isInstalling -> stringResource(R.string.home_update_banner_text)
        progressPercent >= 100 -> stringResource(R.string.home_update_banner_installing)
        else -> stringResource(R.string.home_update_banner_downloading, progressPercent.coerceAtLeast(0))
    }
    CvCard(color = tinted, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_update_banner_title, tag.removePrefix("v")),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isInstalling) {
                    Spacer(Modifier.height(6.dp))
                    if (progressPercent in 0..99) {
                        LinearProgressIndicator(
                            progress = { progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isInstalling) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onUpdate) {
                    Text(stringResource(R.string.home_update_banner_button))
                }
            }
        }
    }
}

/**
 * The flagship hero status banner. READY uses a confident teal-tinted surface with a check;
 * problem states use a warm warning/coral tint with a warning glyph, making the call to action
 * unmistakable.
 */
@Composable
private fun HeroStatusCard(
    status: HomeViewModel.HomeStatus,
    onAction: (() -> Unit)? = null,
) {
    val brand = LocalCvBrand.current
    val accent: Color = if (status.isReady) MaterialTheme.colorScheme.primary else brand.warning
    val icon: ImageVector = if (status.isReady) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber
    val tone = when (status) {
        HomeViewModel.HomeStatus.READY -> CvTone.Success
        HomeViewModel.HomeStatus.NOT_PAIRED -> CvTone.Warning
        HomeViewModel.HomeStatus.NO_FOLDER -> CvTone.Error
        HomeViewModel.HomeStatus.DEV_OPTIONS_OFF -> CvTone.Error
        HomeViewModel.HomeStatus.UPDATE_REGRANT_NEEDED -> CvTone.Warning
    }
    val pillText = when (status) {
        HomeViewModel.HomeStatus.READY -> stringResource(R.string.home_hero_pill_ready)
        HomeViewModel.HomeStatus.NOT_PAIRED -> stringResource(R.string.home_hero_pill_not_paired)
        HomeViewModel.HomeStatus.NO_FOLDER -> stringResource(R.string.home_hero_pill_no_folder)
        HomeViewModel.HomeStatus.DEV_OPTIONS_OFF -> stringResource(R.string.home_hero_pill_dev_options_off)
        HomeViewModel.HomeStatus.UPDATE_REGRANT_NEEDED -> stringResource(R.string.home_hero_pill_update_regrant)
    }

    // Subtle accent-tinted surface so the banner reads as a confident state, not a stock card.
    val tinted = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)

    CvCard(color = tinted, onClick = onAction, contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_hero_status_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(status.titleResId),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(status.suggestionResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        CvStatusPill(text = pillText, tone = tone)
    }
}

/** One selectable entry inside a filter chip's dropdown menu. */
private data class FilterOption<T>(val value: T, val label: String)

/**
 * A wrapping [FlowRow] of compact, content-sized filter chips — one per facet
 * (Source / Direction / Contact / Date). Chips wrap to additional lines so all four are visible
 * without horizontal swiping. Each chip never wraps internally (single line, sized to content) and
 * opens a [DropdownMenu] of that facet's options. The Contact and Date facets are populated
 * dynamically from the currently loaded recordings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingFilterBar(
    sourceFilter: SourceFilter,
    directionFilter: DirectionFilter,
    contactFilter: String?,
    dateFilter: String?,
    availableContacts: List<String>,
    availableDates: List<String>,
    onSourceFilterChange: (SourceFilter) -> Unit,
    onDirectionFilterChange: (DirectionFilter) -> Unit,
    onContactFilterChange: (String?) -> Unit,
    onDateFilterChange: (String?) -> Unit
) {
    // Source facet options + current value label.
    val sourceOptions = listOf(
        FilterOption(SourceFilter.ALL, stringResource(R.string.home_filter_source_all)),
        FilterOption(SourceFilter.LOCAL, stringResource(R.string.home_filter_source_local)),
        FilterOption(SourceFilter.DRIVE, stringResource(R.string.home_filter_source_drive))
    )
    val sourceValueLabel = sourceOptions.first { it.value == sourceFilter }.label

    // Direction facet options + current value label.
    val directionOptions = listOf(
        FilterOption(DirectionFilter.ALL, stringResource(R.string.home_filter_direction_all)),
        FilterOption(DirectionFilter.INCOMING, stringResource(R.string.home_filter_direction_incoming)),
        FilterOption(DirectionFilter.OUTGOING, stringResource(R.string.home_filter_direction_outgoing))
    )
    val directionValueLabel = directionOptions.first { it.value == directionFilter }.label

    // Contact facet: dropdown keeps the full "All contacts" wording; the chip itself shows the
    // compact "All" so Contact + Date fit together on one line.
    val allContactsLabel = stringResource(R.string.home_filter_contact_all)
    val allContactsShort = stringResource(R.string.home_filter_contact_all_short)
    val contactOptions = buildList<FilterOption<String?>> {
        add(FilterOption(null, allContactsLabel))
        availableContacts.forEach { add(FilterOption(it, it)) }
    }
    val contactValueLabel = contactFilter ?: allContactsShort

    // Date facet: dropdown keeps "All dates"; the chip itself shows the compact "All".
    val allDatesLabel = stringResource(R.string.home_filter_date_all)
    val allDatesShort = stringResource(R.string.home_filter_date_all_short)
    val dateOptions = buildList<FilterOption<String?>> {
        add(FilterOption(null, allDatesLabel))
        availableDates.forEach { add(FilterOption(it, it)) }
    }
    val dateValueLabel = dateFilter ?: allDatesShort

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            text = stringResource(R.string.home_filter_source_chip, sourceValueLabel),
            active = sourceFilter != SourceFilter.ALL,
            options = sourceOptions,
            selected = sourceFilter,
            onSelected = onSourceFilterChange
        )
        FilterChip(
            text = stringResource(R.string.home_filter_direction_chip, directionValueLabel),
            active = directionFilter != DirectionFilter.ALL,
            options = directionOptions,
            selected = directionFilter,
            onSelected = onDirectionFilterChange
        )
        FilterChip(
            text = stringResource(R.string.home_filter_contact_chip, contactValueLabel),
            active = contactFilter != null,
            options = contactOptions,
            selected = contactFilter,
            onSelected = onContactFilterChange
        )
        FilterChip(
            text = stringResource(R.string.home_filter_date_chip, dateValueLabel),
            active = dateFilter != null,
            options = dateOptions,
            selected = dateFilter,
            onSelected = onDateFilterChange
        )
    }
}

/**
 * A single compact filter chip + its dropdown. Sized to its content with a single, ellipsized line
 * (never wraps). When [active] (a non-default value is selected) it fills with a teal tint so the
 * user can see at a glance which facets are narrowing the list.
 */
@Composable
private fun <T> FilterChip(
    text: String,
    active: Boolean,
    options: List<FilterOption<T>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val containerColor =
        if (active) primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (active) primary else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor =
        if (active) primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant

    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(containerColor)
                .border(1.dp, borderColor, CircleShape)
                .clickable { expanded = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (option.value == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    }
                )
            }
        }
    }
}

/**
 * A small, subtle pill indicating where a recording is stored: a smartphone glyph for LOCAL, a
 * cloud glyph for DRIVE, and both glyphs for BOTH. Uses a muted/teal tone to stay clean within the row.
 */
@Composable
private fun SourceBadge(source: RecordingSource) {
    val label = when (source) {
        RecordingSource.LOCAL -> stringResource(R.string.home_source_badge_local)
        RecordingSource.DRIVE -> stringResource(R.string.home_source_badge_drive)
        RecordingSource.BOTH -> stringResource(R.string.home_source_badge_both)
    }
    val color = when (source) {
        RecordingSource.LOCAL -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (source == RecordingSource.LOCAL || source == RecordingSource.BOTH) {
            Icon(
                imageVector = Icons.Filled.Smartphone,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
        }
        if (source == RecordingSource.BOTH) Spacer(Modifier.width(3.dp))
        if (source == RecordingSource.DRIVE || source == RecordingSource.BOTH) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
        }
        // BOTH: show only the two glyphs (no text — it truncated to "Dev…").
        // Single-source rows keep their text label alongside the icon.
        if (source != RecordingSource.BOTH) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Friendly centered empty state shown when there are no recordings yet. */
@Composable
private fun EmptyRecordings() {
    CvCard(contentPadding = PaddingValues(vertical = 36.dp, horizontal = 24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_recordings_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_recordings_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * What a confirmed delete should remove. Drives both the confirm dialog message and the action:
 *  - [All]        — every same-named copy (delete-both, BOTH rows' main delete).
 *  - [Single]     — a single-source row's only copy (generic message).
 *  - [DeviceCopy] — only the Device copy of a BOTH recording.
 *  - [DriveCopy]  — only the Drive copy of a BOTH recording.
 */
private sealed interface DeleteTarget {
    data object All : DeleteTarget
    data class Single(val uri: Uri) : DeleteTarget
    data class DeviceCopy(val uri: Uri) : DeleteTarget
    data class DriveCopy(val uri: Uri) : DeleteTarget
}

/**
 * A single recording row: a circular teal disc (play affordance), name/date/number, and size.
 *
 * Behaviour depends on where the recording lives:
 *  - **Single-source** (LOCAL or DRIVE): tapping the row or disc plays its primary [uri] and the
 *    inline teal player (progress slider + elapsed/total + play/pause) is revealed beneath.
 *  - **BOTH**: tapping the row toggles an expanded dropdown listing the Device and Drive copies, each
 *    individually playable via its own teal disc and individually deletable via its own delete icon;
 *    the inline player attaches to whichever copy is playing. A chevron signals expandability.
 *
 * The main-row delete button deletes the whole recording: for single-source rows that is its only
 * copy ([onDeleteUri] on [RecordingItem.uri]); for BOTH rows it deletes every same-named copy
 * ([onDeleteAll]). Per-copy deletion lives inside the BOTH row's expanded sub-entries.
 *
 * @param onDeleteAll Deletes every same-named copy of this recording (delete-both).
 * @param onDeleteUri Deletes a single physical copy at the given Uri.
 */
@Composable
private fun RecordingRow(
    item: RecordingItem,
    playback: RecordingPlaybackController.PlaybackState,
    deleting: Boolean,
    onPlayUri: (Uri) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteUri: (Uri) -> Unit
) {
    // The pending delete target drives the confirm dialog: null = closed.
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val isBoth = item.source == RecordingSource.BOTH

    // Which copies' Uris belong to this row — used to decide whether the row is "active" overall.
    val rowUris = remember(item) { listOfNotNull(item.uri, item.localUri, item.driveUri) }
    val activeUri = playback.activeUri
    val isRowActive = activeUri != null && activeUri in rowUris

    // Prefer the contact name, then the parsed number, then the raw file name.
    val primaryLabel = item.contactName ?: item.number ?: item.displayName

    deleteTarget?.let { target ->
        val message = when (target) {
            is DeleteTarget.All, is DeleteTarget.Single ->
                stringResource(R.string.home_delete_confirm_message, primaryLabel)
            is DeleteTarget.DeviceCopy ->
                stringResource(R.string.home_delete_confirm_device_message, primaryLabel)
            is DeleteTarget.DriveCopy ->
                stringResource(R.string.home_delete_confirm_drive_message, primaryLabel)
        }
        DeleteRecordingDialog(
            name = primaryLabel,
            message = message,
            onConfirm = {
                deleteTarget = null
                when (target) {
                    is DeleteTarget.All -> onDeleteAll()
                    is DeleteTarget.Single -> onDeleteUri(target.uri)
                    is DeleteTarget.DeviceCopy -> onDeleteUri(target.uri)
                    is DeleteTarget.DriveCopy -> onDeleteUri(target.uri)
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }

    val cardColor =
        if (isRowActive) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surface

    // Single-source rows play on tap; BOTH rows toggle the expanded copy list on tap.
    val onCardClick: () -> Unit = {
        if (isBoth) {
            expanded = !expanded
        } else if (!isRowActive) {
            onPlayUri(item.uri)
        }
    }

    CvCard(
        onClick = onCardClick,
        color = cardColor,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // BOTH rows aren't directly playable from the disc; show a non-active disc as an affordance
            // hint but keep playback state attached to the active sub-copy if one is playing.
            PlayDisc(
                direction = item.direction,
                isActive = !isBoth && isRowActive,
                playback = playback
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = primaryLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildSubtitle(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    SourceBadge(source = item.source)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatSize(item.sizeBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // BOTH rows expose an expand chevron; single-source rows expose only delete.
            if (isBoth) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.home_copies_collapse else R.string.home_copies_expand
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            // Main-row delete: BOTH rows delete every copy; single-source rows delete their one file.
            // While the delete (and any cloud-copy removal) is in flight, show a live spinner in the
            // delete button's place — the row then vanishes on the list refresh. No modal.
            if (deleting) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(
                    onClick = {
                        deleteTarget = if (isBoth) DeleteTarget.All else DeleteTarget.Single(item.uri)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.home_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Single-source: inline player directly under the row when this copy is active.
        if (!isBoth && isRowActive) {
            Spacer(Modifier.height(12.dp))
            InlinePlayer(
                playback = playback,
                onPlay = { onPlayUri(item.uri) },
                onPause = onPause,
                onResume = onResume,
                onSeek = onSeek
            )
        }

        // BOTH: expanded dropdown listing each copy, each individually playable.
        if (isBoth && expanded) {
            Spacer(Modifier.height(12.dp))
            item.localUri?.let { uri ->
                CopySubEntry(
                    source = RecordingSource.LOCAL,
                    label = stringResource(R.string.home_copy_device),
                    sizeBytes = item.localSizeBytes,
                    uri = uri,
                    isActive = activeUri == uri,
                    playback = playback,
                    onPlayUri = onPlayUri,
                    onPause = onPause,
                    onResume = onResume,
                    onSeek = onSeek,
                    deleteContentDescription = stringResource(R.string.home_delete_copy_device_content_desc),
                    onDelete = { deleteTarget = DeleteTarget.DeviceCopy(uri) }
                )
            }
            if (item.localUri != null && item.driveUri != null) Spacer(Modifier.height(8.dp))
            item.driveUri?.let { uri ->
                CopySubEntry(
                    source = RecordingSource.DRIVE,
                    label = stringResource(R.string.home_copy_drive),
                    sizeBytes = item.driveSizeBytes,
                    uri = uri,
                    isActive = activeUri == uri,
                    playback = playback,
                    onPlayUri = onPlayUri,
                    onPause = onPause,
                    onResume = onResume,
                    onSeek = onSeek,
                    deleteContentDescription = stringResource(R.string.home_delete_copy_drive_content_desc),
                    onDelete = { deleteTarget = DeleteTarget.DriveCopy(uri) }
                )
            }
        }
    }
}

/**
 * One sub-entry inside a BOTH row's expanded section, representing a single physical copy (Device or
 * Drive). Tapping its teal disc plays THAT copy ([uri]); when it is the active copy the shared inline
 * player (seek bar etc.) is shown beneath it so progress attaches to the copy that is actually playing.
 */
@Composable
private fun CopySubEntry(
    source: RecordingSource,
    label: String,
    sizeBytes: Long?,
    uri: Uri,
    isActive: Boolean,
    playback: RecordingPlaybackController.PlaybackState,
    onPlayUri: (Uri) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    deleteContentDescription: String,
    onDelete: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val containerColor =
        if (isActive) primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
        else MaterialTheme.colorScheme.surfaceVariant
    val isLoading = isActive && playback.phase == RecordingPlaybackController.Phase.LOADING
    val isPlaying = isActive && playback.phase == RecordingPlaybackController.Phase.PLAYING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor)
            .clickable { if (!isActive) onPlayUri(uri) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = if (isActive) 0.22f else 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = primary
                    )
                    else -> Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.home_play_recording),
                        tint = primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // Source glyph to the left of the Device/Drive tag.
            Icon(
                imageVector = if (source == RecordingSource.DRIVE) {
                    Icons.Filled.Cloud
                } else {
                    Icons.Filled.Smartphone
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSize(sizeBytes ?: 0L),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            // Per-copy delete: removes ONLY this physical copy, leaving the other untouched.
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = deleteContentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (isActive) {
            Spacer(Modifier.height(10.dp))
            InlinePlayer(
                playback = playback,
                onPlay = { onPlayUri(uri) },
                onPause = onPause,
                onResume = onResume,
                onSeek = onSeek
            )
        }
    }
}

/**
 * Circular teal disc that anchors each row. Shows a small loading spinner while preparing, a pause
 * glyph while the row is the active playing track, and otherwise a play arrow. A tiny direction
 * badge (incoming / outgoing) overlays the bottom-right corner.
 */
@Composable
private fun PlayDisc(
    direction: RecordingDirection?,
    isActive: Boolean,
    playback: RecordingPlaybackController.PlaybackState
) {
    val primary = MaterialTheme.colorScheme.primary
    val isLoading = isActive && playback.phase == RecordingPlaybackController.Phase.LOADING
    val isPlaying = isActive && playback.phase == RecordingPlaybackController.Phase.PLAYING

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = if (isActive) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = primary
                )
                else -> Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.home_play_recording),
                    tint = primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        DirectionBadge(direction)
    }
}

/** Tiny corner badge indicating call direction, overlaid on the play disc. */
@Composable
private fun BoxScope.DirectionBadge(direction: RecordingDirection?) {
    val icon = when (direction) {
        RecordingDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
        RecordingDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
        null -> return
    }
    val description = when (direction) {
        RecordingDirection.INCOMING -> stringResource(R.string.general_incoming)
        RecordingDirection.OUTGOING -> stringResource(R.string.general_outgoing)
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .align(Alignment.BottomEnd),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(11.dp)
        )
    }
}

/**
 * Confirmation dialog shown before permanently deleting a recording (or one copy of it).
 *
 * @param message The pre-resolved confirmation message. Defaults to the delete-all-copies wording;
 *                callers deleting a single Device/Drive copy pass a copy-specific message instead.
 */
@Composable
private fun DeleteRecordingDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String = stringResource(R.string.home_delete_confirm_message, name)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
        title = { Text(text = stringResource(R.string.home_delete_confirm_title)) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.home_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.general_cancel))
            }
        }
    )
}

/** The inline player controls (loading / error / play-pause + teal seek slider + elapsed/total). */
@Composable
private fun InlinePlayer(
    playback: RecordingPlaybackController.PlaybackState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit
) {
    when (playback.phase) {
        RecordingPlaybackController.Phase.LOADING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_player_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        RecordingPlaybackController.Phase.ERROR -> {
            Text(
                text = stringResource(R.string.home_player_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        else -> {
            val isPlaying = playback.phase == RecordingPlaybackController.Phase.PLAYING
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (isPlaying) onPause() else onResume() }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.home_player_pause else R.string.home_player_play
                        ),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = formatMillis(playback.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SeekBar(
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    onSeek = onSeek,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = formatMillis(playback.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// -------- Formatting helpers

/** Builds the muted subtitle line: "date · number" when both are present, else whichever exists. */
private fun buildSubtitle(item: RecordingItem): String {
    val date = item.displayDate
    // If the primary label is already the number, avoid repeating it on the subtitle.
    val numberShown = item.contactName != null
    val number = item.number?.takeIf { numberShown }
    return when {
        date != null && number != null -> "$date · $number"
        date != null -> date
        number != null -> number
        else -> item.displayName
    }
}

/** Formats a byte count as a compact human-readable size (e.g. "1.2 MB"). */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}

/**
 * A thin, thumbless progress bar for the inline player — cleaner than a Slider but still
 * tap- and drag-seekable: tapping or dragging anywhere along it scrubs to that position.
 */
@Composable
private fun SeekBar(
    positionMs: Int,
    durationMs: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = durationMs.takeIf { it > 0 } ?: 1
    val fraction = (positionMs.toFloat() / total).coerceIn(0f, 1f)
    val widthPx = remember { mutableStateOf(0) }
    fun seekToX(x: Float) {
        val w = widthPx.value
        if (w > 0) onSeek(((x / w).coerceIn(0f, 1f) * total).toInt())
    }
    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { widthPx.value = it.width }
            .pointerInput(Unit) { detectTapGestures { offset -> seekToX(offset.x) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> seekToX(offset.x) },
                    onHorizontalDrag = { change, _ -> seekToX(change.position.x); change.consume() },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Formats a millisecond duration as m:ss. */
private fun formatMillis(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
