/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.system.storage.RetentionPolicy
import com.baba.callvault.utils.HONOR_DIALER_TEMPLATE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proof of authorship for the retention sweep's untracked pass (review C10). Other apps' audio may share the folder;
 * a date somewhere in its name must not make it ours to delete.
 */
class AuthoredNameTest {

    private val default = "{date}_{direction}_{contact_name}"

    /** The sweep's real question: parsed as the list parses it, then asked whether it may age out. */
    private fun eligible(name: String, template: String = default) =
        RetentionPolicy.isEligible(name, RecordingsRepository.parseName(name).startedAtMillis, template)

    @Test
    fun `other apps' audio with a day and time in the name is never ours`() {
        listOf(
            "2024-03-05 14-22-10.m4a",
            "audio_2024-03-05_14-22-10.ogg",
            "rec_20240305T142210Z.m4a",
            "Recording_20240305_142210.m4a",
            "Call_20240305_142210_in_0501234567.m4a",
        ).forEach { assertFalse(it, eligible(it)) }
    }

    @Test
    fun `every built-in date preset and the app-call grammar stay eligible`() {
        listOf(
            "20260804_101010.123+0300_in_Dana.ogg",
            "20260804_101010.123+0300_out_+15551234.m4a",
            "20260804_101010.123+0300_+15551234.ogg",
            "Dana_+15551234_20260804_101010.123+0300.ogg",
            "+15551234_Dana_20260804_101010.123-0500.ogg",
            "Dana_20260804_101010.123+0300.ogg",
            "20260804_101010.123+0300_voip.ogg",
            "20260804_101010.123+0300_voip-WhatsApp_Dana.ogg",
            "20260804_101010.123+0300_in_voip-Telegram_Dana.ogg",
            // A merged recording: the primary's name with the millisecond nudged.
            "20260804_101010.124+0300_in_Dana.ogg",
        ).forEach { assertTrue(it, eligible(it)) }
    }

    @Test
    fun `a day-and-time name is ours only while that template is the active one`() {
        val custom = "{contact_name}_{day-}_{time-}"
        val name = "Dana_2026-08-04_10-10-10.ogg"
        assertTrue(eligible(name, custom))
        assertFalse("written under an earlier template: left alone", eligible(name))
    }

    @Test
    fun `the Honor shape is ours only while the Honor preset is active`() {
        val name = "Dana@+972501234567_20260804101010.m4a"
        assertTrue(eligible(name, HONOR_DIALER_TEMPLATE))
        assertFalse(eligible(name))
    }

    @Test
    fun `literal text in a custom template must be there`() {
        val custom = "call {date} {direction}"
        assertTrue(AuthoredName.isCallVaultName("call 20260804_101010.123+0300 in.ogg", custom))
        assertFalse(AuthoredName.isCallVaultName("talk 20260804_101011 in.ogg", custom))
    }
}
