/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.provider.Settings
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.baba.callvault.R
import kotlinx.coroutines.flow.first
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.AppLock
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.viewmodels.SettingsActions
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.ui.platform.LocalResources
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * The app lock, and for now nothing else.
 *
 * Its own sub-section rather than a row in Visual, because it is the first setting here that is about
 * who may *see* the app rather than how it looks, and because the next few privacy settings will want
 * somewhere obvious to go.
 */
@Composable
internal fun PrivacySubSection(preferences: AppPreferences, updateTrigger: Int) {
    val context = LocalContext.current
    // Re-read on every settings change: the user may have gone to system Settings and added a screen
    // lock precisely because this row told them to, and coming back to a still-disabled row would
    // read as the app being wrong.
    val available = remember(updateTrigger) { AppLock.isAvailable(context) }
    var enabled by remember(updateTrigger) { mutableStateOf(preferences.isAppLockEnabled()) }
    var allowScreenshots by remember(updateTrigger) { mutableStateOf(preferences.isAllowScreenshots()) }

    SettingsToggleRow(
        label = stringResource(R.string.settings_app_lock_title),
        checked = enabled && available,
        onCheckedChange = {
            enabled = it
            preferences.setAppLockEnabled(it)
            // The secure flag is derived from the lock being on; re-apply so turning the lock off also
            // lifts the screenshot block straight away rather than on the next visit.
            (context as? com.baba.callvault.MainActivity)?.applySecureFlag()
        },
        // Disabled rather than hidden when the phone has no screen lock: hiding it would leave
        // someone looking for a feature they have read about with no idea why it is absent.
        enabled = available,
        description = stringResource(
            if (available) R.string.settings_app_lock_description
            else R.string.settings_app_lock_unavailable
        ),
        descriptionBelow = true,
    )

    // Only meaningful while the lock is on — that is the only time the secure flag is set — so it rides
    // under the lock row and is disabled when the lock is off.
    if (enabled && available) {
        SettingsToggleRow(
            label = stringResource(R.string.settings_allow_screenshots_title),
            checked = allowScreenshots,
            onCheckedChange = {
                allowScreenshots = it
                preferences.setAllowScreenshots(it)
                // Apply immediately so a screenshot works now, not after leaving and re-entering.
                (context as? com.baba.callvault.MainActivity)?.applySecureFlag()
            },
            description = stringResource(R.string.settings_allow_screenshots_description),
            descriptionBelow = true,
        )
    }
}

/**
 * The app's look: its language, theme, dynamic colour and toasts. Shared with the new Settings screen's
 * "App" group; vibration, the last row of Visual on the classic screen, is [VibrationRow].
 */
@Composable
internal fun AppearanceRows(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val currentThemeMode = remember(updateTrigger) { preferences.getThemeMode() }
    val isDynamicColorEnabled = remember(updateTrigger) { preferences.isDynamicColorEnabled() }
    val isShowToastsEnabled = remember(updateTrigger) { preferences.isShowToastsEnabled() }
    val context = LocalContext.current
    val resources = LocalResources.current

    // Read the current applied language without warnings
    val currentLanguage = remember {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) "" else currentLocales[0]?.toLanguageTag() ?: ""
    }

    // Fetch available languages from dynamically generated XML resource file.
    val languageOptions = remember(context) {
        val options = mutableListOf(OptionItem("", resources.getString(R.string.settings_language_system)))

        // Suppress the warning right here since AGP create this file dynamically at compile time
        @SuppressLint("DiscouragedApi")
        val resId = resources.getIdentifier("_generated_res_locale_config", "xml", context.packageName)

        try {
            val parser = resources.getXml(resId)
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    val localeName = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    if (localeName != null) {
                        val locale = Locale.forLanguageTag(localeName)
                        val displayName = locale.getDisplayName(locale).replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                        }
                        options.add(OptionItem(localeName, displayName))
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            options.add(OptionItem("en", "English (Provided as fallback)"))
        }
        options.distinctBy { it.key }
    }

    run {
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.settings_language),
                selected = languageOptions.find { it.key == currentLanguage } ?: languageOptions.first(),
                options = languageOptions,
                onOptionSelected = { actions.setAppLanguage(it.key) }
            )
        }

        val themeOptions = AppPreferences.ThemeMode.entries.map { mode ->
            val labelRes = when (mode) {
                AppPreferences.ThemeMode.SYSTEM -> R.string.settings_theme_mode_system
                AppPreferences.ThemeMode.LIGHT -> R.string.settings_theme_mode_light
                AppPreferences.ThemeMode.DARK -> R.string.settings_theme_mode_dark
            }
            OptionItem(mode.key, stringResource(labelRes))
        }
        val defaultThemeMode = AppPreferences.DefaultsValue.THEME_MODE.key

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_theme_mode),
                selected = themeOptions.find { it.key == currentThemeMode.key }
                    ?: themeOptions.find { it.key == defaultThemeMode }
                    ?: themeOptions.first(),
                options  = themeOptions,
                onOptionSelected = { actions.setThemeMode(AppPreferences.ThemeMode.fromKey(it.key)) }
            )
        }

        SettingsDivider()

        SettingsToggleRow(
            icon            = Icons.Filled.ColorLens,
            label           = stringResource(R.string.settings_dynamic_color),
            checked         = isDynamicColorEnabled,
            onCheckedChange = { actions.setDynamicColorEnabled(it) }
        )
        SettingsToggleRow(
            icon            = Icons.Filled.NotificationsActive,
            label           = stringResource(R.string.settings_show_toasts),
            checked         = isShowToastsEnabled,
            onCheckedChange = { actions.setShowToastsEnabled(it) }
        )
    }
}

/** Vibrate when a recording starts and stops. */
@Composable
internal fun VibrationRow(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val isVibrationEnabled = remember(updateTrigger) { preferences.isVibrationEnabled() }
    SettingsToggleRow(
        icon            = Icons.Filled.Vibration,
        label           = stringResource(R.string.settings_vibration_enabled),
        checked         = isVibrationEnabled,
        onCheckedChange = { actions.setVibrationEnabled(it) }
    )
}

/**
 * The removed classic Experimental ▸ Resilience rows, in the Settings screen's "Connection" order (round 2, 2026-10-21):
 * what keeps the recorder reachable first, then what keeps a recording alive, then the Wireless debugging
 * details.
 */
@Composable
internal fun ConnectionRows() {
    UsbDebuggingToggle()
    SettingsDivider()
    OfflineRecordingToggle()
    SettingsDivider()
    HandoffPersistToggle()
    SettingsDivider()
    WirelessDebuggingModeRow()
    SettingsDivider()
    WifiNetworkAutoApproveToggle()
    SettingsDivider()
    UsbDefaultConfigRow()
    PairAgainRow()
}

/** A branded, collapsible section: a tappable [CvSectionHeader] (with a rotating chevron) above a
 * [CvCard] grouping its rows. Tapping the header invokes [onToggle]; the body animates in and out.
 * The expand/collapse state is HOISTED to the parent so the Settings screen can run an accordion
 * (at most one section open at a time); this composable is stateless about expansion.
 *
 * @param title    Section heading shown above the card; the whole header row toggles the section.
 * @param expanded Whether this section's body is currently shown.
 * @param onToggle Invoked when the header is tapped (the parent decides the new open-section).
 * @param content  The slot for child rows rendered inside the [CvCard] when expanded.
 */
/**
 * In-app updates: a daily release check (on by default). A new release surfaces as a notification +
 * Home banner, and installs only on an explicit tap.
 */
@Composable
internal fun UpdatesSubSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions
) {
    val isCheckEnabled = remember(updateTrigger) { preferences.isUpdateCheckEnabled() }

    Column {
        SettingsToggleRow(
            icon = Icons.Filled.SystemUpdate,
            label = stringResource(R.string.settings_update_check_label),
            checked = isCheckEnabled,
            onCheckedChange = { actions.setUpdateCheckEnabled(it) },
            description = stringResource(R.string.settings_update_check_description)
        )
    }
}
