/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.expandableHeader
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R

/** One-line status under a settings row (arming progress, or why something is unavailable). */
@Composable
internal fun SettingsHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
    )
}

/** Small label separating groups of related rows inside one collapsible section. */
@Composable
internal fun SettingsSubHeader(text: String, nested: Boolean = false, modifier: Modifier = Modifier) {
    // General ▸ Experimental ▸ Resilience is three levels deep. Rendering the inner grouping at the
    // same weight as its parent makes the two read as siblings, so a nested header is quieter and
    // indented instead of introducing a second component.
    Text(
        text = text,
        style = if (nested) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
        color = if (nested) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(
            start = if (nested) 20.dp else 12.dp,
            top = if (nested) 10.dp else 12.dp,
            bottom = if (nested) 2.dp else 8.dp,
        )
    )
}

/**
 * True while a section is drawn inside a group card on the new Settings screen ([ScopedSettingsContent]).
 * The card then owns the header, the folding and the frame, so [SettingsSection] draws only its rows.
 *
 * False by default, which is the classic screen: it never provides this, so it renders exactly as before.
 */
internal val LocalSettingsSectionInGroupCard = staticCompositionLocalOf { false }

/**
 * True inside the new Settings screen's group cards: rows then show their name and value only, and an ⓘ
 * button opens the explanation. False by default, which is the classic screen and the wizard, so they render
 * exactly as before. A disabled row keeps its description inline, since it is then the reason it is disabled.
 */
internal val LocalSettingsDescriptionsAsInfo = staticCompositionLocalOf { false }

/** Size of the ⓘ touch target: big enough to hit beside a switch. */
private val INFO_BUTTON_SIZE = 44.dp

/** The ⓘ button that replaces a row's description in the new Settings screen; tapping it explains the row. */
@Composable
internal fun SettingsInfoButton(label: String, explanation: String) {
    var showing by remember { mutableStateOf(false) }
    IconButton(onClick = { showing = true }, modifier = Modifier.size(INFO_BUTTON_SIZE)) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(R.string.settings_info_about, label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(label) },
            text = { Text(explanation) },
            confirmButton = {
                TextButton(onClick = { showing = false }) { Text(stringResource(R.string.settings_info_got_it)) }
            },
        )
    }
}

/** A row's explanations for its ⓘ: the description, a blank line, then the hint. Null when there are none. */
internal fun joinExplanations(vararg parts: String?): String? =
    parts.filterNot { it.isNullOrBlank() }.joinToString("\n\n").takeIf { it.isNotEmpty() }

/**
 * A free-standing EXPLANATORY hint under a row. Drawn as [SettingsHint] on the classic screen; in ⓘ mode it
 * draws nothing, because the row it explains carries it in its ⓘ (pass the same text as that row's
 * `infoExtra`, or wrap the control in [WithInfo]). Live status and warnings use [SettingsHint] directly.
 */
@Composable
internal fun SettingsExplanation(text: String) {
    if (!LocalSettingsDescriptionsAsInfo.current) SettingsHint(text)
}

/** [SettingsExplanation] for the smaller [HintText] used under dropdowns. */
@Composable
internal fun HintExplanation(text: String) {
    if (!LocalSettingsDescriptionsAsInfo.current) HintText(text)
}

/**
 * An explanatory paragraph that belongs to a whole group (retention, the transcription mode, the summariser's
 * costs). Drawn on the classic screen; in ⓘ mode the group card's own ⓘ carries it instead.
 */
@Composable
internal fun ExplanationParagraph(text: String) {
    if (LocalSettingsDescriptionsAsInfo.current) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * A control with no ⓘ of its own (a dropdown) gets one beside it in ⓘ mode, carrying [explanation]. On the
 * classic screen [content] is drawn as it always was, with nothing around it.
 */
@Composable
internal fun WithInfo(label: String, explanation: String?, content: @Composable () -> Unit) {
    if (!LocalSettingsDescriptionsAsInfo.current || explanation.isNullOrBlank()) {
        content()
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { content() }
        SettingsInfoButton(label = label, explanation = explanation)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    // A section made of sub-sections passes false: the card then belongs around each OPEN
    // sub-section's rows, not around a list of labels that do nothing until tapped. Wrapping the
    // labels made a closed section read as a floating menu of empty items.
    wrapInCard: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    if (LocalSettingsSectionInGroupCard.current) {
        // The same rows, without this section's own header and fold: the group card around it has both.
        Column(verticalArrangement = Arrangement.spacedBy(if (wrapInCard) 0.dp else 6.dp)) { content() }
        return
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsSectionChevron"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .expandableHeader(expanded, onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CvSectionHeader(text = title, modifier = Modifier.weight(1f))
            // A section takes the accent, matching its teal dash; a sub-section gets a small muted
            // triangle instead (see [SettingsSubSection]). Differing in BOTH shape and colour is what
            // makes the two levels tellable apart at a glance — size alone did not.
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(22.dp)
                    .rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            if (wrapInCard) {
                CvCard(contentPadding = PaddingValues(vertical = 8.dp)) { content() }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
            }
        }
    }
}

/**
 * A collapsible group INSIDE a section — Storage ▸ Retention, General ▸ Updates, and so on.
 *
 * Same interaction as [SettingsSection] so the two levels behave alike, and the same shape: a bare
 * header row, with the card around the CONTENT once it opens. The header is quieter than a section
 * title — ordinary text rather than the accent colour — so it reads as a level down rather than as a
 * competing heading.
 *
 * Callers hold the open key themselves, one per parent section, which makes the sub-sections an
 * accordion within their own section only — opening Retention cannot close something in General.
 */
@Composable
internal fun SettingsSubSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsSubSectionChevron"
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .expandableHeader(expanded, onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsSubHeader(title, modifier = Modifier.weight(1f))
            // Triangle, not a chevron, and muted rather than accented — the opposite of the section
            // marker above it on both counts.
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp).size(24.dp).rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            CvCard(contentPadding = PaddingValues(vertical = 8.dp)) { content() }
        }
    }
}

/** A thin inset divider used to separate row clusters inside a [CvCard]. */
@Composable
internal fun SettingsDivider() {
    // outlineVariant is Material's subtlest rule, and against this app's very dark surface it all but
    // disappears — reported as "barely visible in dark theme". Deriving the colour from
    // onSurfaceVariant instead keeps it theme-aware (it inverts with the scheme) while guaranteeing
    // contrast against whatever surface it lands on, in light and dark alike.
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    )
}

/** Wraps a [M3DropdownField] (and optional hint) so it slots cleanly inside a [CvCard]. */
@Composable
internal fun DropdownRow(content: @Composable ColumnScope.() -> Unit) {
    Column(content = content)
}

/** Indents and tints a nested option cluster revealed under an auto-record toggle. */
@Composable
internal fun NestedGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        content = content
    )
}

/** A small muted helper line shown beneath a dropdown/field. */
@Composable
internal fun HintText(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

/** Circular tinted leading-icon badge used by settings rows. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * A tappable navigation/dialog row: leading icon, label + value (+ optional supporting hint), and a
 * trailing chevron. Used for folder pickers, the filename template, and the About rows.
 */
@Composable
internal fun NavigationRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    showChevron: Boolean = true,
    // True when [supporting] is a value or a warning rather than an explanation, so the new screen's
    // ⓘ mode ([LocalSettingsDescriptionsAsInfo]) must still show it on the row.
    keepSupportingInline: Boolean = false,
    // An explanatory paragraph that the classic screen draws under this row (the row's caller draws it with
    // [SettingsExplanation]). In ⓘ mode it joins the row's ⓘ instead; ignored otherwise.
    infoExtra: String? = null,
    // The value's colour; a caller whose value is a "still to do" prompt can tint it instead of the teal default.
    valueColor: Color = MaterialTheme.colorScheme.primary,
) {
    val infoMode = LocalSettingsDescriptionsAsInfo.current
    val supportingAsInfo = infoMode && supporting != null && !keepSupportingInline
    val infoText = if (infoMode) joinExplanations(supporting.takeIf { supportingAsInfo }, infoExtra) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (supporting != null && !supportingAsInfo) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (infoText != null) {
            SettingsInfoButton(label = label, explanation = infoText)
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * A switch row styled for the Signal cards: optional leading icon, label + supporting text, and a
 * teal [Switch]. Tapping anywhere on the row toggles it. Mirrors the behavior of the shared
 * ToggleListItem while matching the redesigned row anatomy.
 */
@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true,
    descriptionBelow: Boolean = false,
    // True when [description] is a value or a status rather than an explanation, so the new screen's
    // ⓘ mode ([LocalSettingsDescriptionsAsInfo]) must still show it on the row.
    keepDescriptionInline: Boolean = false,
    // An explanatory paragraph that the classic screen draws under this row (the row's caller draws it with
    // [SettingsExplanation]). In ⓘ mode it joins the row's ⓘ instead; ignored otherwise.
    infoExtra: String? = null,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    // A disabled row's description is the reason it is disabled (e.g. not available in this mode): a
    // warning, so it stays on the row even in ⓘ mode.
    val descriptionAsInfo = LocalSettingsDescriptionsAsInfo.current && description != null &&
        enabled && !keepDescriptionInline
    val infoText = if (LocalSettingsDescriptionsAsInfo.current) {
        joinExplanations(description.takeIf { descriptionAsInfo }, infoExtra)
    } else null

    @Composable
    fun descriptionText(modifier: Modifier = Modifier) {
        if (description == null || descriptionAsInfo) return
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            modifier = modifier,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The row is the switch: one TalkBack stop that reads the label, the state and the role.
            // The ⓘ button inside stays its own stop.
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                RowIcon(icon)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )
                if (!descriptionBelow) {
                    Spacer(Modifier.height(2.dp))
                    descriptionText()
                }
            }
            Spacer(Modifier.width(12.dp))
            if (infoText != null) {
                SettingsInfoButton(label = label, explanation = infoText)
            }
            // Display only (the row toggles). The minimum size keeps the 48dp slot a clickable Switch has.
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
        }
        if (descriptionBelow && description != null && !descriptionAsInfo) {
            // Full width, under the switch rather than in the narrow column beside it.
            //
            // Sharing the column with the label means wrapping against roughly two thirds of the
            // screen, so a status line that runs to two lines at full width takes four — and the row
            // grows tallest exactly when it has the most to say, which is when something is wrong.
            Spacer(Modifier.height(4.dp))
            descriptionText(Modifier.fillMaxWidth())
        }
    }
}
