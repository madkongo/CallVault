/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.VoipCallAppFilter.PackageFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 103: a recording named "Charging (59 m until full)" after System UI was taken for the call app. */
class VoipCallAppFilterTest {

    private val systemUi = PackageFacts("com.android.systemui", isSystem = true, hasLauncher = false)
    private val whatsapp = PackageFacts("com.whatsapp", isSystem = false, hasLauncher = true)
    private val telegram = PackageFacts("org.telegram.messenger", isSystem = false, hasLauncher = true)
    private val unknown = -1

    private fun resolve(
        answers: (Set<Int>) -> Int,
        packages: Map<Int, List<PackageFacts>>,
        lines: MutableList<String> = mutableListOf(),
    ) = VoipCallAppFilter.resolve("start", unknown, answers, { packages[it].orEmpty() }, { lines.add(it) })

    @Test
    fun `System UI is not a calling app`() {
        assertFalse(VoipCallAppFilter.isCallApp(listOf(systemUi)))
        assertFalse(VoipCallAppFilter.isCallApp(listOf(PackageFacts("android", true, false))))
        // Decided by what it is, not only by name: a system package nobody can open is no call app either.
        assertFalse(VoipCallAppFilter.isCallApp(listOf(PackageFacts("com.vendor.battery.service", true, false))))
    }

    @Test
    fun `WhatsApp, Telegram and an openable preinstalled app are calling apps`() {
        assertTrue(VoipCallAppFilter.isCallApp(listOf(whatsapp)))
        assertTrue(VoipCallAppFilter.isCallApp(listOf(telegram)))
        assertTrue(VoipCallAppFilter.isCallApp(listOf(PackageFacts("com.google.android.apps.tachyon", true, true))))
        // Installed without a launcher entry (a work-profile or stripped app) is still user-installed.
        assertTrue(VoipCallAppFilter.isCallApp(listOf(PackageFacts("org.example.voip", false, false))))
    }

    @Test
    fun `a uid shared by System UI and a calling app is accepted for the calling app`() {
        assertTrue(VoipCallAppFilter.isCallApp(listOf(systemUi, whatsapp)))
    }

    @Test
    fun `System UI from the first source is rejected and the next source answers`() {
        val asked = mutableListOf<Set<Int>>()
        val lines = mutableListOf<String>()
        val result = resolve(
            answers = { rejected -> asked.add(rejected.toSet()); if (10314 in rejected) 10304 else 10314 },
            packages = mapOf(10314 to listOf(systemUi), 10304 to listOf(telegram)),
            lines = lines,
        )
        assertEquals(10304, result?.uid)
        assertEquals(listOf(emptySet(), setOf(10314)), asked)
        assertTrue(lines.any { "uid 10314" in it && "rejected" in it })
        assertTrue(lines.any { "uid 10304" in it && "accepted" in it && "org.telegram.messenger" in it })
    }

    @Test
    fun `when only System UI answers there is no call app and it is not asked for forever`() {
        var asks = 0
        val result = resolve(
            answers = { rejected -> asks++; if (10314 in rejected) unknown else 10314 },
            packages = mapOf(10314 to listOf(systemUi)),
        )
        assertNull(result)
        assertEquals(2, asks)
    }

    @Test
    fun `a daemon that repeats a rejected uid is not asked again`() {
        var asks = 0
        val result = resolve(answers = { asks++; 10314 }, packages = mapOf(10314 to listOf(systemUi)))
        assertNull(result)
        assertEquals(2, asks)
    }

    @Test
    fun `WhatsApp is accepted on the first answer and logged without any notification text`() {
        val lines = mutableListOf<String>()
        val result = resolve({ 10135 }, mapOf(10135 to listOf(whatsapp)), lines)
        assertEquals(10135, result?.uid)
        assertEquals(1, lines.size)
        assertTrue("start" in lines[0] && "uid 10135" in lines[0] && "com.whatsapp" in lines[0] && "accepted" in lines[0])
    }

    @Test
    fun `no answer and no visible package give no app`() {
        assertNull(resolve({ unknown }, emptyMap()))
        assertNull(resolve({ 10500 }, emptyMap()))
    }
}
