/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.baba.callvault.data.AppPreferences
import java.util.Locale

/**
 * A context whose strings follow the language chosen inside CallVault (Settings > Language), else the phone's.
 *
 * The per-app language is applied by Android to activities; a service, a worker or a bare application context
 * can still format text in the system language. A notification built from such a context came out in English
 * next to Chinese ones on a Chinese-language app (BabApps item 123). Every notification's text goes through
 * [of]; `NotificationTextLocaleGuardTest` fails when a builder reads strings from a context that skipped it.
 */
object LocalizedContext {

    /** The app's chosen locale, or null when none is set (the phone's language then applies). */
    fun appLocale(base: Context): Locale? {
        val fromCompat = runCatching {
            AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)
        }.getOrNull()
        // AppCompat only knows the locale it was told about in this process; on Android 13+ ask the system,
        // which is where setApplicationLocales stores it.
        val systemKnows = Build.VERSION.SDK_INT >= 33
        val fromSystem = if (systemKnows) {
            runCatching {
                base.getSystemService(LocaleManager::class.java)?.applicationLocales
                    ?.takeIf { !it.isEmpty }?.get(0)
            }.getOrNull()
        } else null
        val prefs = runCatching { AppPreferences(base) }.getOrNull()
        // Below Android 13 nothing else remembers the choice in a process that never opened an activity (a
        // boot, a worker, a notification after an update): AppCompat answers only once an activity loaded it
        // (review C143). Keep the mirror current whenever the real answer is in hand.
        if (fromCompat != null) prefs?.let { mirror(it, fromCompat) }
        return resolve(fromCompat, fromSystem, prefs?.getAppLanguageTag(), systemKnows)
    }

    /**
     * Which locale wins: what AppCompat loaded in this process, else what Android 13+ reports, else - below
     * Android 13 only - the tag mirrored in our own preferences. On 13+ the system is the truth (the user can
     * change the per-app language in Android's settings without opening CallVault), so a mirror never overrides
     * a system that says "no per-app language".
     */
    internal fun resolve(compat: Locale?, system: Locale?, storedTag: String?, systemKnowsLocale: Boolean): Locale? {
        if (compat != null) return compat
        if (systemKnowsLocale) return system
        return storedTag?.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) }
    }

    private fun mirror(prefs: AppPreferences, locale: Locale) {
        val tag = locale.toLanguageTag()
        if (prefs.getAppLanguageTag() != tag) prefs.setAppLanguageTag(tag)
    }

    fun of(base: Context): Context {
        val locale = appLocale(base) ?: return base
        return runCatching {
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            base.createConfigurationContext(config)
        }.getOrDefault(base)
    }
}
