/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C158: back goes to the newest registered enabled handler. The Settings page's handler must be
 * registered when the page opens (inside its visible content), not once when Home is first composed,
 * or Home's own handlers (open recording, reading view) answer first while Settings covers them.
 */
class SettingsSidebarBackOrderTest {

    private val source = listOf(
        "src/main/java/com/baba/callvault/ui/common/SettingsSidebar.kt",
        "app/src/main/java/com/baba/callvault/ui/common/SettingsSidebar.kt",
    ).map(::File).first { it.isFile }.readText()

    @Test
    fun `the page's back handler is registered with the page, after Home's`() {
        val visibility = source.indexOf("AnimatedVisibility(")
        val handler = source.indexOf("BackHandler(")
        assertTrue("AnimatedVisibility must exist", visibility >= 0)
        assertTrue("BackHandler must be composed inside the visible page", handler > visibility)
    }
}
