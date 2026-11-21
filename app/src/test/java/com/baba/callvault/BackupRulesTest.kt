/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * allowBackup=false does not keep an app out of a device-to-device transfer on Android 12+ (review C87): the
 * rules file decides. Everything must be excluded, or the ADB private key and the recordings' database ride
 * along to a phone where they mean nothing.
 */
class BackupRulesTest {

    private val domains = listOf("root", "file", "database", "sharedpref", "external")

    private fun xml(name: String): String =
        File("src/main/res/xml/$name").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun section(text: String, tag: String): String =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            ?: error("no <$tag> section")

    private fun assertExcludesEverything(body: String, where: String) {
        domains.forEach { domain ->
            assertTrue(
                "$where must exclude the $domain domain",
                Regex("""<exclude\s+domain="$domain"\s+path="\."\s*/>""").containsMatchIn(body),
            )
        }
    }

    @Test
    fun `device transfer excludes every domain`() {
        val rules = xml("data_extraction_rules.xml")
        assertExcludesEverything(section(rules, "device-transfer"), "device-transfer")
    }

    @Test
    fun `cloud backup excludes every domain`() {
        val rules = xml("data_extraction_rules.xml")
        assertExcludesEverything(section(rules, "cloud-backup"), "cloud-backup")
    }

    @Test
    fun `the Android 11 and older backup rules exclude every domain`() {
        assertExcludesEverything(section(xml("backup_rules.xml"), "full-backup-content"), "full-backup-content")
    }
}
