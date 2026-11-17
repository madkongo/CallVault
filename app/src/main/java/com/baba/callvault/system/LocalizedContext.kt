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
        if (fromCompat != null) return fromCompat
        // AppCompat only knows the locale it was told about in this process; on Android 13+ ask the system,
        // which is where setApplicationLocales stores it.
        if (Build.VERSION.SDK_INT >= 33) {
            return runCatching {
                base.getSystemService(LocaleManager::class.java)?.applicationLocales
                    ?.takeIf { !it.isEmpty }?.get(0)
            }.getOrNull()
        }
        return null
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
