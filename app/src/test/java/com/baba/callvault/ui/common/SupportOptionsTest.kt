package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class SupportOptionsTest {
    @Test fun dropdownListsKofiThenPayPalForEveryone() =
        assertEquals(listOf(SupportOption.KOFI, SupportOption.PAYPAL), SUPPORT_OPTIONS)
}
