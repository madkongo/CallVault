package com.baba.callvault.ui.common

import com.baba.callvault.system.BUYMEACOFFEE_SUPPORT_URL
import com.baba.callvault.system.USDT_TON_ADDRESS
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportOptionsTest {
    @Test fun dropdownListsAllFourOptionsInOrderForEveryone() =
        assertEquals(
            listOf(SupportOption.KOFI, SupportOption.PAYPAL, SupportOption.BUYMEACOFFEE, SupportOption.USDT_TON),
            SUPPORT_OPTIONS,
        )

    /** Character for character: capital I in "adqIiJ-", zero in "Fi09". A typo here loses donations. */
    @Test fun tonAddressIsExactlyTheMaintainersWallet() =
        assertEquals("UQBmQTlgwFi09QZAnwgyDFv_6OL9qqXVt-JZDxcD2adqIiJ-", USDT_TON_ADDRESS)

    @Test fun buyMeACoffeeUrlIsTheMaintainersPage() =
        assertEquals("https://buymeacoffee.com/madkongo", BUYMEACOFFEE_SUPPORT_URL)
}
