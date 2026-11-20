/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class RomDetectorTest {
    private fun detect(maker: String, vararg props: Pair<String, String>) =
        RomDetector.detect(props.toMap().let { m -> { k: String -> m[k] ?: "" } })

    @Test fun unknownMakerWithNoPropertiesIsUnknown() {
        assertEquals(Rom(RomFamily.UNKNOWN, null), detect("acme"))
    }

    @Test fun oplusRomIsColorOsFamily() {
        assertEquals(RomFamily.COLOROS, detect("OnePlus", "ro.build.version.oplusrom" to "V16.0.0").family)
        assertEquals("V16.0.0", detect("OnePlus", "ro.build.version.oplusrom" to "V16.0.0").version)
    }

    @Test fun hyperOsWinsOverMiuiProperties() {
        val rom = detect("Xiaomi", "ro.mi.os.version.name" to "OS3.0", "ro.miui.ui.version.name" to "V150")
        assertEquals(RomFamily.HYPEROS, rom.family)
        assertEquals("OS3.0", rom.version)
    }

    @Test fun miuiAlone() {
        assertEquals(RomFamily.MIUI, detect("Xiaomi", "ro.miui.ui.version.name" to "V130").family)
    }

    @Test fun magicOsAndEmui() {
        assertEquals(RomFamily.MAGICOS, detect("HONOR", "ro.build.version.magic" to "11.0").family)
        assertEquals(RomFamily.EMUI, detect("HUAWEI", "ro.build.version.emui" to "EmotionUI_12").family)
    }

    @Test fun oneUiVersionIsDecoded() {
        val rom = detect("samsung", "ro.build.version.oneui" to "80500")
        assertEquals(RomFamily.ONE_UI, rom.family)
        assertEquals("8.5", rom.version)
    }

    @Test fun garbledOneUiVersionKeepsTheRawValue() {
        assertEquals("abc", detect("samsung", "ro.build.version.oneui" to "abc").version)
    }

    @Test fun vivoProperty() {
        assertEquals(RomFamily.VIVO, detect("vivo", "ro.vivo.os.version" to "15.0").family)
    }

    @Test fun aReaderThatThrowsGivesUnknown() {
        assertEquals(RomFamily.UNKNOWN, RomDetector.detect { error("boom") }.family)
    }
}
