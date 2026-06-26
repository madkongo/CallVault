package com.baba.callvault.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class DialerModePrefTest {
    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test fun defaults_to_false() { assertFalse(prefs.isDialerModeEnabled()) }

    @Test fun persists_true() {
        prefs.setDialerModeEnabled(true)
        assertTrue(prefs.isDialerModeEnabled())
    }
}
