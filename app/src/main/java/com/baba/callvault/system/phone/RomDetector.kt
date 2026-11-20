/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

/**
 * Names the ROM from vendor system properties. 📐 Property names come from public ROM documentation and our
 * own reports; a missing or renamed property gives UNKNOWN, which is the main path. Pure: [get] reads one property
 * ("" when absent).
 */
object RomDetector {

    fun detect(maker: String, get: (String) -> String): Rom = runCatching { detectUnsafe(get) }
        .getOrDefault(Rom(RomFamily.UNKNOWN, null))

    private fun detectUnsafe(get: (String) -> String): Rom {
        fun prop(key: String): String? = get(key).trim().ifEmpty { null }
        // Order matters: HyperOS phones still carry the older MIUI property.
        prop("ro.mi.os.version.name")?.let { return Rom(RomFamily.HYPEROS, it) }
        prop("ro.miui.ui.version.name")?.let { return Rom(RomFamily.MIUI, it) }
        prop("ro.build.version.magic")?.let { return Rom(RomFamily.MAGICOS, it) }
        prop("ro.build.version.emui")?.let { return Rom(RomFamily.EMUI, it) }
        prop("ro.vivo.os.version")?.let { return Rom(RomFamily.VIVO, it) }
        (prop("ro.build.version.oplusrom") ?: prop("ro.build.version.opporom"))
            ?.let { return Rom(RomFamily.COLOROS, it) }
        prop("ro.build.version.oneui")?.let { return Rom(RomFamily.ONE_UI, oneUiVersion(it)) }
        return Rom(RomFamily.UNKNOWN, null)
    }

    /** One UI stores 8.5 as 80500; anything else is kept as the raw value. */
    private fun oneUiVersion(raw: String): String {
        val n = raw.toIntOrNull() ?: return raw
        return "${n / 10000}.${n % 10000 / 100}"
    }
}
