package com.baba.callvault.ui.screens

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageDisplayNameTest {

    @Test
    fun hong_kong_and_taiwan_are_named_as_traditional_chinese() {
        assertEquals("繁體中文（香港）", appLanguageDisplayName("zh-HK"))
        assertEquals("繁體中文（台灣）", appLanguageDisplayName("zh-TW"))
    }

    @Test
    fun other_locales_keep_the_platform_name() {
        listOf("zh-CN", "de", "fr").forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            val expected = locale.getDisplayName(locale).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(locale) else it.toString()
            }
            assertEquals(expected, appLanguageDisplayName(tag))
        }
    }
}
