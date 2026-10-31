/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.R

/**
 * Shared "Signal" UI building blocks. Screens should compose with these instead of raw Material
 * widgets so the redesign stays consistent. All colors come from MaterialTheme.colorScheme /
 * [LocalCvBrand], so they adapt to light/dark automatically.
 */

/** Screen container with a clean brand top bar (title + optional back + trailing actions). */
@Composable
fun CvScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Opaque: the content below scrolls UNDER this bar, so a transparent header lets
                    // rows slide through the title. Only visible once the list is long enough to
                    // scroll, which is why it went unnoticed until Settings moved into a panel.
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            // Resourced, not a literal: this scaffold is the top bar on Home,
                            // Settings, Playback and the wizard, so an English literal here is the
                            // one thing a screen-reader user hears untranslated on every screen.
                            contentDescription = stringResource(R.string.general_back),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }
                // When a [titleTrailing] element is present it sits right next to the title (a weighted
                // spacer then pushes [actions] to the far edge); otherwise the title column itself takes
                // the remaining width, preserving the original layout for every existing caller.
                if (titleTrailing == null) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    // Title and pill share what is left once [actions] are placed: weighted children are
                    // measured last, so the settings icon can never be pushed off screen. When they do not
                    // fit, the title gives way (one line, ellipsised) rather than the icon.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle != null) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        titleTrailing()
                    }
                }
                actions()
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** A rounded, bordered surface card — the default container for grouped content. */
@Composable
fun CvCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    border: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val borderStroke = if (border) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null

    // Long-press needs `combinedClickable`, which Surface's own onClick cannot express. Only cards
    // that ask for it take this path, so every existing caller keeps the Surface-click behaviour it
    // had — including its ripple and its disabled state when onClick is null.
    if (onLongClick != null) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick),
            color = color,
            shape = shape,
            border = borderStroke,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
        return
    }

    val base = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clip(shape) else Modifier)
    Surface(
        modifier = modifier.then(base),
        onClick = onClick ?: {},
        enabled = onClick != null,
        color = color,
        shape = shape,
        border = borderStroke,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Small uppercase section label with a short accent bar — used to title groups. */
@Composable
fun CvSectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 14.dp, height = 3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Large screen hero (big title + optional subtitle). */
@Composable
fun CvHero(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Full-width filled primary (teal) action button. */
@Composable
fun CvPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Full-width tonal secondary action button. */
@Composable
fun CvSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Full-width destructive action button, red, for an action that removes something that exists — the same height and
 * shape as [CvPrimaryButton]. The colours are stated rather than left to M3's defaults, which render in this app's
 * coral rather than a clear red.
 */
@Composable
fun CvDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

enum class CvTone {
    Success,
    Warning,
    Error,
    Neutral,
    Accent,

    /**
     * Informational, not a state — for a label that says *what* something is rather than whether it is
     * healthy. Uses the brand's own `info` colour deliberately: `Neutral` resolves to
     * `onSurfaceVariant`, which in this app's scheme is the coral tone, and a coral badge reads as an
     * error to anyone glancing at it.
     */
    Info,
}

/** A compact status pill with a leading dot — used for state ("Ready", "Required", etc.). */
@Composable
fun CvStatusPill(text: String, tone: CvTone, modifier: Modifier = Modifier) {
    val brand = LocalCvBrand.current
    val color = when (tone) {
        CvTone.Success -> brand.success
        CvTone.Warning -> brand.warning
        CvTone.Error -> MaterialTheme.colorScheme.error
        CvTone.Accent -> MaterialTheme.colorScheme.primary
        CvTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        CvTone.Info -> brand.info
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
