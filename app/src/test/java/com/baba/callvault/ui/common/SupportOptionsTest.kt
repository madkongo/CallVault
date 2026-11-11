package com.baba.callvault.ui.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportOptionsTest {
    private val withBoosty = listOf(SupportOption.KOFI, SupportOption.PAYPAL, SupportOption.BOOSTY)
    private val without = listOf(SupportOption.KOFI, SupportOption.PAYPAL)

    private fun loc(tag: String) = Locale.forLanguageTag(tag)

    @Test fun russianAppShowsBoosty() = assertEquals(withBoosty, supportOptions(loc("ru"), loc("en-US")))
    @Test fun ukrainianAppShowsBoosty() = assertEquals(withBoosty, supportOptions(loc("uk"), null))
    @Test fun polishAppShowsBoosty() = assertEquals(withBoosty, supportOptions(loc("pl"), null))
    @Test fun hungarianAppShowsBoosty() = assertEquals(withBoosty, supportOptions(loc("hu"), null))

    @Test fun russianPhoneWithEnglishAppHidesBoosty() =
        assertEquals(without, supportOptions(loc("en"), loc("ru-RU")))

    @Test fun unsetAppFollowsRussianPhone() = assertEquals(withBoosty, supportOptions(null, loc("ru-RU")))
    @Test fun emptyAppListFollowsPhone() = assertEquals(withBoosty, supportOptions(Locale.ROOT, loc("pl-PL")))
    @Test fun englishPhoneInEastEuropeanCountry() = assertEquals(withBoosty, supportOptions(null, loc("en-RO")))

    @Test fun usEnglishHidesBoosty() = assertEquals(without, supportOptions(null, loc("en-US")))
    @Test fun hebrewHidesBoosty() = assertEquals(without, supportOptions(loc("he"), loc("he-IL")))
    @Test fun noLocaleHidesBoosty() = assertEquals(without, supportOptions(null, null))
    @Test fun emptyLocalesHideBoosty() = assertEquals(without, supportOptions(Locale.ROOT, Locale.ROOT))

    @Test fun everyListedLanguageAndCountryShowsBoosty() {
        BOOSTY_LANGUAGES.forEach { assertEquals(it, withBoosty, supportOptions(Locale(it), null)) }
        BOOSTY_COUNTRIES.forEach { assertEquals(it, withBoosty, supportOptions(null, Locale("en", it))) }
    }
}
