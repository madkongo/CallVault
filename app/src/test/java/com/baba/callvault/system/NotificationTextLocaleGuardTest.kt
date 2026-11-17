/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BabApps item 123: notification text built from a context that ignored the app's language came out in English
 * next to Chinese notices. Every file that builds a notification must route its text through [LocalizedContext].
 * A new notifier that skips it fails here.
 */
class NotificationTextLocaleGuardTest {

    private val builders = listOf("NotificationCompat.Builder(", "Notification.Builder(")

    @Test
    fun every_file_that_builds_a_notification_uses_the_localized_context() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { text -> builders.any { it in text } } }
            .filter { !it.readText().contains("LocalizedContext") }
            .map { it.name }
            .toList()
        assertTrue(
            "These build notifications from a context that may ignore the app language: $offenders. " +
                "Take the text from LocalizedContext.of(context).",
            offenders.isEmpty(),
        )
    }
}
