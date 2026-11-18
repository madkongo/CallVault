/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.baba.callvault.R
import com.baba.callvault.system.openBuyMeACoffee
import com.baba.callvault.system.openKofi
import com.baba.callvault.system.openPayPal

/** The places the Support dialog can send a donation to. */
enum class SupportOption { KOFI, PAYPAL, BUYMEACOFFEE, USDT_TON }

/** What tapping a row does: open a page in the browser, or show the wallet page inside the dialog. */
sealed interface SupportAction {
    class Browser(val open: Context.() -> Unit) : SupportAction
    data object Wallet : SupportAction
}

/** One row of the Support dialog. A new donation option is one more entry in [SUPPORT_OPTIONS]. */
data class SupportEntry(
    val option: SupportOption,
    @StringRes val label: Int,
    @StringRes val secondLine: Int,
    val icon: ImageVector,
    val tint: Color,
    val action: SupportAction,
)

/** What the Support dialog lists, in order. The same for everyone. */
val SUPPORT_OPTIONS: List<SupportEntry> = listOf(
    SupportEntry(
        SupportOption.KOFI, R.string.support_kofi, R.string.support_opens_in_browser,
        Icons.Filled.Favorite, Color(0xFF0E8F7E), SupportAction.Browser { openKofi() },
    ),
    SupportEntry(
        SupportOption.PAYPAL, R.string.support_paypal, R.string.support_opens_in_browser,
        Icons.Filled.CreditCard, Color(0xFF1D6FB8), SupportAction.Browser { openPayPal() },
    ),
    SupportEntry(
        SupportOption.BUYMEACOFFEE, R.string.support_buymeacoffee, R.string.support_opens_in_browser,
        Icons.Filled.Coffee, Color(0xFFE07B00), SupportAction.Browser { openBuyMeACoffee() },
    ),
    SupportEntry(
        SupportOption.USDT_TON, R.string.support_usdt_ton, R.string.support_copy_wallet_address,
        Icons.Filled.Paid, Color(0xFF2E9E4F), SupportAction.Wallet,
    ),
)
