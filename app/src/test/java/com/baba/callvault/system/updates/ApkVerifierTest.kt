/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class ApkVerifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sha256_hex_matches_known_vector() {
        // SHA-256("abc") is a standard test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkVerifier.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun missing_or_empty_file_is_rejected() {
        val missing = File(context.cacheDir, "does-not-exist.apk")
        assertFalse(ApkVerifier.isValidUpdate(context, missing))

        val empty = File(context.cacheDir, "empty.apk").apply { writeBytes(ByteArray(0)) }
        assertFalse(ApkVerifier.isValidUpdate(context, empty))
    }

    @Test
    fun garbage_file_is_rejected() {
        val garbage = File(context.cacheDir, "garbage.apk").apply { writeText("not an apk") }
        assertFalse(ApkVerifier.isValidUpdate(context, garbage))
    }
}
