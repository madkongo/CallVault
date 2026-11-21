/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import androidx.annotation.StringRes
import com.baba.callvault.R

/**
 * Which battery-saver wording a phone gets (review R6). The setting that lets a background app live has a
 * different name and extra steps per maker; the OnePlus wording sent everyone else to a setting that does not exist.
 * Text only: the screen it is shown on is unchanged. Pure: the caller passes Build.MANUFACTURER (and the brand).
 */
object BatteryAdvice {

    enum class Maker { ONEPLUS, XIAOMI, SAMSUNG, HUAWEI_HONOR, VIVO, OPPO_REALME, OTHER }

    fun makerOf(manufacturer: String?, brand: String? = null): Maker {
        val m = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
        return when {
            "oneplus" in m -> Maker.ONEPLUS
            "xiaomi" in m || "redmi" in m || "poco" in m -> Maker.XIAOMI
            "samsung" in m -> Maker.SAMSUNG
            "huawei" in m || "honor" in m -> Maker.HUAWEI_HONOR
            "vivo" in m || "iqoo" in m -> Maker.VIVO
            "oppo" in m || "realme" in m -> Maker.OPPO_REALME
            else -> Maker.OTHER
        }
    }

    @StringRes
    fun hintRes(maker: Maker): Int = when (maker) {
        Maker.ONEPLUS -> R.string.permission_battery_oem_hint
        Maker.XIAOMI -> R.string.permission_battery_hint_xiaomi
        Maker.SAMSUNG -> R.string.permission_battery_hint_samsung
        Maker.HUAWEI_HONOR -> R.string.permission_battery_hint_huawei_honor
        Maker.VIVO -> R.string.permission_battery_hint_vivo
        Maker.OPPO_REALME -> R.string.permission_battery_hint_oppo_realme
        Maker.OTHER -> R.string.permission_battery_hint_other
    }
}
