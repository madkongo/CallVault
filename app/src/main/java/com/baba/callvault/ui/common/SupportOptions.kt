/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import java.util.Locale

/** The places the Support dialog can send a donation to, in the order they are listed. */
enum class SupportOption { KOFI, PAYPAL, BOOSTY }

/** Languages of East Europe, where Boosty is the usual way to pay. Lower-case ISO 639 codes. */
val BOOSTY_LANGUAGES: Set<String> = setOf(
    "ru", "uk", "be", "kk", "pl", "cs", "sk", "hu", "bg", "ro", "sr", "hr", "sl", "lt", "lv", "et",
)

/** The matching countries (ISO 3166 upper-case), for a phone whose language is English but whose region is not. */
val BOOSTY_COUNTRIES: Set<String> = setOf(
    "RU", "UA", "BY", "KZ", "PL", "CZ", "SK", "HU", "BG", "RO", "RS", "HR", "SI", "LT", "LV", "EE",
)

private fun Locale.isEastEurope(): Boolean =
    language.lowercase(Locale.ROOT) in BOOSTY_LANGUAGES || country.uppercase(Locale.ROOT) in BOOSTY_COUNTRIES

/**
 * Which donation options to show. Ko-fi and PayPal always; Boosty only for East Europe.
 *
 * The app's own language decides first: someone who picked English in the app but has a Russian phone
 * asked for English and is not shown Boosty. Only when no app language is chosen ([appLocale] null or
 * empty) do the phone's language and region decide ([phoneLocale]). No locale at all means no Boosty.
 */
fun supportOptions(appLocale: Locale?, phoneLocale: Locale?): List<SupportOption> {
    val chosen = appLocale?.takeIf { it.language.isNotEmpty() || it.country.isNotEmpty() } ?: phoneLocale
    val showBoosty = chosen?.isEastEurope() == true
    return if (showBoosty) listOf(SupportOption.KOFI, SupportOption.PAYPAL, SupportOption.BOOSTY)
    else listOf(SupportOption.KOFI, SupportOption.PAYPAL)
}
