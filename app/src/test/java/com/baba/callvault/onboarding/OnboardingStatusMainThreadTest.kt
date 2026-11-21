/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C85: [OnboardingStatus.getStatus] runs on the main thread (init, every resume), so it must not take
 * libadb's connection lock, which pairing holds for the whole exchange. The persisted pairing flag
 * is what answers; every successful connect already writes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingStatusMainThreadTest {

    @Test
    fun `status never asks libadb for a live connection`() {
        val source = listOf(
            "src/main/java/com/baba/callvault/onboarding/OnboardingStatus.kt",
            "app/src/main/java/com/baba/callvault/onboarding/OnboardingStatus.kt",
        ).map(::File).first { it.isFile }.readText()

        assertFalse(
            "getStatus must not touch AdbConnectionManager (its lock is held during pairing)",
            Regex("""AdbConnectionManager\s*\.\s*getInstance""").containsMatchIn(source)
        )
    }

    @Test
    fun `status follows the persisted pairing flag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AppPreferences(context)

        preferences.setAdbPaired(false)
        assertFalse(OnboardingStatus.getStatus(context, preferences).adbConnected)

        preferences.setAdbPaired(true)
        assertTrue(OnboardingStatus.getStatus(context, preferences).adbConnected)
    }
}
