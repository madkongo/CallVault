package com.baba.callvault.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriorDefaultDialerPrefTest {
    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test fun defaults_to_null() { assertNull(prefs.getPriorDefaultDialer()) }

    @Test fun stores_and_clears() {
        prefs.setPriorDefaultDialer("com.google.android.dialer")
        assertEquals("com.google.android.dialer", prefs.getPriorDefaultDialer())
        prefs.setPriorDefaultDialer(null)
        assertNull(prefs.getPriorDefaultDialer())
    }
}
