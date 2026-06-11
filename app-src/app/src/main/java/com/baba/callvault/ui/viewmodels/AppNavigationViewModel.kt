/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.onboarding.OnboardingStatus
import com.baba.callvault.ui.navigation.AppScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The "Brain" of the top-level navigation routing.
 *
 * Owns the [onboardingStatus] `StateFlow` that [AppNavigationScreen] observes to decide
 * which onboarding destination to show (disclaimer → permissions → wizard → home), plus the
 * [manualScreen] override that lets the user step OFF the resolved screen for in-app navigation
 * (Home ↔ Settings) since this app has no Jetpack NavHost.
 */
class AppNavigationViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Application context - safe to store in a ViewModel because it lives as long as the app
     * process, unlike an Activity context which is destroyed and recreated on every rotation.
     */
    private val appContext = application.applicationContext

    /**
     * Read and Manage AppPreferences
     */
    private val preferences = AppPreferences(appContext)

    // ------ Internal mutable state

    /**
     * Backing store for [onboardingStatus].
     * Updated by [refresh]; never exposed directly so external code cannot push arbitrary values.
     */
    private val _onboardingStatus = MutableStateFlow(
        OnboardingStatus.getStatus(appContext, preferences)
    )

    // ------ Public state (AppNavigationScreen watches this)

    /**
     * The current onboarding progress - a "Snapshot" of every permission and setup step.
     *
     * [AppNavigationScreen] uses `collectAsState()` to observe this flow; whenever a permission
     * is granted or the disclaimer is accepted, [refresh] pushes a new [OnboardingStatus.Status]
     * which triggers a refresh (recompose) and the router advances to the correct screen.
     */
    val onboardingStatus: StateFlow<OnboardingStatus.Status> = _onboardingStatus.asStateFlow()

    // ------ Manual navigation override (Home ↔ Settings)

    /**
     * Backing store for [manualScreen]. When non-null it overrides the resolved onboarding screen,
     * letting the user navigate to a screen ([AppScreen.Settings]) that is not part of the linear
     * onboarding flow. Cleared by [navigateBack].
     */
    private val _manualScreen = MutableStateFlow<AppScreen?>(null)

    /**
     * An optional manual destination that takes precedence over the resolved onboarding screen.
     *
     * The router only honours this once onboarding has fully resolved to [AppScreen.Home] — if the
     * onboarding state regresses (e.g. a permission is revoked), the manual override is ignored so the
     * user is pulled back into the correct onboarding step.
     */
    val manualScreen: StateFlow<AppScreen?> = _manualScreen.asStateFlow()

    /** Navigates to a manual destination (e.g. Settings) on top of the resolved Home screen. */
    fun navigateTo(screen: AppScreen) {
        _manualScreen.update { screen }
    }

    /** Clears the manual destination, returning the user to the resolved onboarding screen (Home). */
    fun navigateBack() {
        _manualScreen.update { null }
    }

    // ------ Refresh

    /**
     * Re-reads all permission and setup states from the system and updates [onboardingStatus].
     *
     * Should be called when the user returns to the app after granting a permission in the system
     * Settings app, or immediately after accepting the disclaimer / granting a permission
     * in-app, so the router advances to the next screen without delay.
     */
    fun refresh() {
        _onboardingStatus.update { OnboardingStatus.getStatus(appContext, preferences) }
    }
}
