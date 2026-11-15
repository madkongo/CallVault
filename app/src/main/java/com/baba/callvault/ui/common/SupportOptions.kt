/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

/** The places the Support dialog can send a donation to. */
enum class SupportOption { KOFI, PAYPAL }

/** What the Donate dropdown lists, in order. The same for everyone. */
val SUPPORT_OPTIONS: List<SupportOption> = listOf(SupportOption.KOFI, SupportOption.PAYPAL)
