/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Signal" brand palette — deep navy surfaces, a bright teal accent, coral highlights.
 * Fixed (non-dynamic) so CallVault has a consistent identity across devices.
 */

// ── Core brand hues ───────────────────────────────────────────────────────────
val Teal        = Color(0xFF2DD4BF) // primary accent
val TealBright  = Color(0xFF5EEAD4)
val TealDeep    = Color(0xFF134E47)
val TealDeepCtr = Color(0xFF0C3A35)
val Coral       = Color(0xFFFB7185) // secondary highlight
val CoralDeep   = Color(0xFF5E1F2A)
val Sky         = Color(0xFF38BDF8) // tertiary accent
val SkyDeep     = Color(0xFF0B3A52)
val Success     = Color(0xFF34D399) // semantic success (status)
val Warning     = Color(0xFFFBBF24) // semantic warning (status)

// ── Dark scheme (the hero) ────────────────────────────────────────────────────
val NavyBg         = Color(0xFF0E1726) // background
val NavySurface    = Color(0xFF16223A) // surface / cards
val NavySurfaceLow = Color(0xFF111B2E) // dimmer container
val NavySurfaceHi  = Color(0xFF1E2D49) // elevated container
val NavySurfaceHi2 = Color(0xFF243558) // highest
val Ice            = Color(0xFFE6EDF5) // primary text on dark
val IceMuted       = Color(0xFF9FB0C4) // muted text
val NavyOutline    = Color(0xFF2E405E)
val NavyOutlineDim = Color(0xFF233247)
val ErrorDark      = Color(0xFFFF6B6B)
val OnErrorDark    = Color(0xFF3A0A0A)
val ErrorCtrDark   = Color(0xFF5C1A1A)
val OnErrorCtrDark = Color(0xFFFFD9D9)

// ── Light scheme ──────────────────────────────────────────────────────────────
val IceBg           = Color(0xFFF4F7FB) // background
val LightSurfaceCv  = Color(0xFFFFFFFF) // surface / cards
val LightSurfaceVar = Color(0xFFE4EAF2) // variant
val DeepNavyText    = Color(0xFF101826) // primary text on light
val SlateMuted      = Color(0xFF4A5A70) // muted text
val LightOutline    = Color(0xFFB7C2D2)
val LightOutlineDim = Color(0xFFD4DCE7)
val TealOnLight     = Color(0xFF0E8C7E) // primary on light (higher contrast)
val TealCtrLight    = Color(0xFFB8F2EA)
val CoralOnLight    = Color(0xFFC2415A)
val CoralCtrLight   = Color(0xFFFFD9DE)
val ErrorLight      = Color(0xFFC8302F)
val SuccessOnLight  = Color(0xFF0E9F6E)
val WarningOnLight  = Color(0xFFB7791F)

val White = Color(0xFFFFFFFF)
