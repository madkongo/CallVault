/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 92: the hidden-setting branch of the pairing wait, and the Android 17 notification wording. */
class PairingWaitPolicyTest {

    @Test
    fun `Android 17 names the path, older Android keeps the plain wording`() {
        assertTrue(PairingWaitPolicy.usesPathWording(37))
        assertTrue(PairingWaitPolicy.usesPathWording(40))
        assertFalse(PairingWaitPolicy.usesPathWording(36))
        assertFalse(PairingWaitPolicy.usesPathWording(33))
    }

    @Test
    fun `the setting may be hidden on Android 17 whatever it reads`() {
        assertTrue(PairingWaitPolicy.settingMayBeHidden(37, 1))
        assertTrue(PairingWaitPolicy.settingMayBeHidden(37, 0))
        assertTrue(PairingWaitPolicy.settingMayBeHidden(37, -1))
    }

    @Test
    fun `on older Android only a read other than on counts as maybe hidden`() {
        assertTrue(PairingWaitPolicy.settingMayBeHidden(35, 0))
        assertTrue(PairingWaitPolicy.settingMayBeHidden(35, -1))
        assertFalse(PairingWaitPolicy.settingMayBeHidden(35, 1))
    }

    private val resDir = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
    private val pattern = Regex("""<string name="notif_pairing_waiting_wd_text_path"[^>]*>(.*?)</string>""")

    @Test
    fun `the English wording is exactly the path the user must follow`() {
        val text = pattern.find(File(resDir, "values/strings_notifications.xml").readText())!!.groupValues[1]
        assertEquals("Open Wireless debugging → Pair device with pairing code", text)
    }

    @Test
    fun `every language has the path wording with an arrow`() {
        val dirs = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .filter { File(it, "strings_notifications.xml").isFile }
        assertTrue(dirs.size >= 18)
        val missing = dirs.filter { dir ->
            val text = pattern.find(File(dir, "strings_notifications.xml").readText())?.groupValues?.get(1)
            text == null || !(text.contains("→") || text.contains("←"))
        }.map { it.name }
        assertTrue("No path wording in: $missing", missing.isEmpty())
    }
}
