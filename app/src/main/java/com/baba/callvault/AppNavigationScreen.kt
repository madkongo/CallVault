/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import com.baba.callvault.ui.common.SettingsSidebar
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.onboarding.OnboardingStatus
import com.baba.callvault.ui.navigation.AppScreen
import com.baba.callvault.ui.navigation.HomeSection
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.ui.screens.DisclaimerScreen
import com.baba.callvault.ui.screens.HomeScreen
import com.baba.callvault.ui.screens.PermissionsScreen
import com.baba.callvault.ui.screens.SettingsScreen
import com.baba.callvault.ui.screens.WizardScreen
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.ui.viewmodels.AppNavigationViewModel
import com.baba.callvault.ui.viewmodels.HomeViewModel
import com.baba.callvault.ui.viewmodels.SettingsViewModel

/**
 * Top-level router composable called from [MainActivity].
 *
 * Decides which of three destinations to display - the one-time disclaimer, the permissions
 * checklist, or the main settings, wraps every destination in the app theme compose.
 *
 * ## State flow
 * - [AppNavigationViewModel] is the "Brain" for routing: it owns [AppNavigationViewModel.onboardingStatus]
 *   and decides which screen is active.
 * - [SettingsViewModel] is the "Brain" for settings: it owns [SettingsViewModel.updateTrigger]
 *   and all user-preference persistence.
 * - Both expose `StateFlow`s observed via [collectAsState] - the "bridge" that watches a data
 *   stream and triggers a refresh (recompose) whenever a value changes.
 * - [LocalLifecycleOwner] is the object that tells this composable whether the current screen
 *   is visible, in the background, or being destroyed. We attach a [LifecycleEventObserver]
 *   via [DisposableEffect] to refresh state whenever the user returns to the app (e.g. after
 *   granting a permission in the system Settings app).
 */
@Composable
fun AppNavigationScreen(
    notificationDestination: NotificationDestination = NotificationDestination.None,
    onNotificationDestinationHandled: () -> Unit = {}
) {

    val activityContext = LocalContext.current

    /** [LocalLifecycleOwner] provides the lifecycle of the current screen (Activity/Fragment).
     *  We observe it so we know when the user navigates back to the app. */
    val lifecycleOwner = LocalLifecycleOwner.current

    // AppNavigationViewModel - the "Brain" for routing: owns onboarding state.
    val appNavViewModel: AppNavigationViewModel = viewModel()

    // SettingsViewModel - the "Brain" for settings: owns theme + preference state.
    val settingsViewModel: SettingsViewModel = viewModel()

    /**
     * [collectAsState] bridges the [AppNavigationViewModel.onboardingStatus] `StateFlow` to Compose.
     * Every time the flow emits a new [OnboardingStatus.Status] value, Compose triggers a
     * refresh (recompose) so [resolveScreen] picks the correct destination.
     */
    val onboardingStatus by appNavViewModel.onboardingStatus.collectAsState()

    /**
     * [collectAsState] bridges the [AppNavigationViewModel.manualScreen] `StateFlow` to Compose.
     * When non-null (and onboarding has resolved to Home), it overrides the resolved screen so the
     * user can step into Settings — there is no Jetpack NavHost, so this is the manual nav mechanism.
     */
    val manualScreen by appNavViewModel.manualScreen.collectAsState()

    /**
     * [collectAsState] bridges the [SettingsViewModel.updateTrigger] `StateFlow` to Compose.
     * Reading allow us to trigger a refresh (recompose) whenever the user changes a setting that requires a
     * major UI update (e.g. theme change) that can only be updated here in the AppNavigationScreen.
     */
    val settingsViewModelUpdateTrigger by settingsViewModel.updateTrigger.collectAsState() // reading .value here is required so it trigger a recomposition as soon as it changes.

    // AppPreferences is used to read preferences directly.
    val preferences = settingsViewModel.preferences

    // Listen for refresh in the SettingsViewModel, as certain settings changes may change some checks in the onboarding status.
    LaunchedEffect(settingsViewModelUpdateTrigger) {
        val newStatus = OnboardingStatus.getStatus(activityContext, preferences)
        if (newStatus != onboardingStatus) {
            appNavViewModel.refresh()
        }
    }

    // resolveScreen reads the flow-backed onboardingStatus - no direct preference reads here,
    // which is what caused the stale-state bug that existed before this architecture.
    val resolvedScreen = resolveScreen(onboardingStatus)

    // The manual override (e.g. Settings) is only honoured once onboarding has fully resolved to Home.
    // If onboarding state regresses (e.g. a permission is revoked), the override is ignored so the
    // router pulls the user back into the correct onboarding step.
    val screenState = if (resolvedScreen == AppScreen.Home && manualScreen != null) manualScreen!! else resolvedScreen

    // [DisposableEffect] attaches a [LifecycleEventObserver] to [lifecycleOwner].
    // When the user returns to the app (ON_RESUME), both ViewModels refresh so the screen
    // reflects any changes made while the app was in the background (e.g. permission granted).
    // [onDispose] removes the observer to prevent leaks when this composable leaves the tree.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                appNavViewModel.refresh()
                settingsViewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- Which section of Home is showing.
    //
    // Read once per visit rather than on every recomposition: this is a SharedPreferences read, and
    // the answer cannot change underneath us — only [goToSection] writes it, and it updates the state
    // below in the same breath.
    val storedSectionKey = remember { preferences.getLastHomeSectionKey() }

    // Null until the user (or a notification) has actually navigated. Until then the section is
    // derived from what was stored, every composition, which is what makes "reopen where you were"
    // land without a frame of the hub first: an effect that resolved it after the first composition
    // would show the hub and then swap.
    var chosenSection by rememberSaveable { mutableStateOf<HomeSection?>(null) }
    val section = chosenSection ?: HomeSection.opening(resolvedScreen, storedSectionKey) ?: HomeSection.Hub

    // Every navigation between sections goes through here, so persisting it cannot be forgotten in
    // one of the places that navigates.
    val goToSection: (HomeSection) -> Unit = { next ->
        chosenSection = next
        preferences.setLastHomeSection(next)
    }

    // A notification tap says what it was about, and now it decides where it lands: all four go to
    // the hub, because the status card, the update offer and the banners are all there. Without
    // this, "recording is broken" could open onto a transcript list with no sign of the problem —
    // silently, since nothing crashes.
    //
    // Acknowledged whatever the router decided, including when it decided onboarding: a notification
    // cannot jump ahead of the disclaimer or the wizard, and a request that outlives the visit it
    // arrived in would re-navigate later, out of nowhere. Logged because "which notification did you
    // tap?" is a question a debug report otherwise cannot answer.
    LaunchedEffect(notificationDestination, screenState) {
        if (notificationDestination == NotificationDestination.None) return@LaunchedEffect
        val target = HomeSection.forNotification(resolvedScreen, notificationDestination)
        if (target != null) goToSection(target)
        AppLogger.d(
            "CV:Nav",
            "Opened from the ${notificationDestination.key} notification; showing $screenState" +
                (target?.let { ", section ${it.key}" } ?: "")
        )
        onNotificationDestinationHandled()
    }

    // Derive the active theme from AppPreferences so a theme change triggers a refresh (recompose)
    // and is applied immediately.
    val darkTheme = when ( preferences.getThemeMode()) {
        AppPreferences.ThemeMode.LIGHT -> false
        AppPreferences.ThemeMode.DARK   -> true
        AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val dynamicColor = preferences.isDynamicColorEnabled()

    // -------- Show the right screen
    CallVaultTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        when (screenState) {

            AppScreen.Disclaimer -> DisclaimerScreen(
                onContinue = {
                    preferences.setDisclaimerAccepted(true)
                    appNavViewModel.refresh()
                }
            )

            AppScreen.Permissions -> PermissionsScreen(
                status              = onboardingStatus,
                onPermissionGranted = { appNavViewModel.refresh() }
            )

            AppScreen.Wizard -> WizardScreen(
                // The wizard persists everything + flips the wizardCompleted flag on Finish; refreshing
                // the onboarding status here advances the router from Wizard to Home.
                onFinished = { appNavViewModel.refresh() }
            )

            // Settings is a PANEL over Home, not a destination: Home stays composed underneath, so
            // closing is instant and nothing behind is rebuilt. SettingsSidebar owns back, the scrim
            // tap and the swipe; the gear only has to open it.
            AppScreen.Home -> {
                val scope = rememberCoroutineScope()
                val drawerState = rememberDrawerState(DrawerValue.Closed)

                // Held here rather than defaulted inside HomeScreen so the resume observer below and
                // the list are demonstrably the same instance. `viewModel()` resolves against the
                // Activity's store, so this is the instance HomeScreen would have built anyway.
                val homeViewModel: HomeViewModel = viewModel()

                // Returning to the app re-runs HomeViewModel.refresh(), and this is the level it has
                // to happen at. refresh() is not a list reload: it recomputes the status card, runs
                // the setup-health sweep and silently re-grants WRITE_SECURE_SETTINGS when an
                // install-over has dropped it — a grant whose absence has already cost a real
                // 13-minute call. Owned by the recordings screen, all of that ran only while the
                // recordings list was what you resumed onto; owned here, it runs whatever section is
                // showing. HomeScreen no longer registers one, so there is still exactly one.
                //
                // Deliberately inside the Home branch and not beside the router's own observer
                // above: during onboarding there is no recorder, no folder and nothing to heal, and
                // building HomeViewModel there would start a full recordings pass behind the wizard.
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) homeViewModel.refresh()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // Back from a section returns to the hub. From the hub, nothing is registered at all
                // and the gesture leaves the app — swallowing it there would make CallVault the one
                // app on the phone you cannot back out of.
                //
                // Registered here, above the panel and above Home, so it is the LAST resort:
                // Compose hands back to the most recently composed enabled handler, and the drawer's
                // (open), selection mode's and the open recording's are all composed below this one.
                // Deliberately inside the Home branch — during onboarding there are no sections, and
                // a handler registered out there would swallow back on the wizard.
                BackHandler(enabled = section != HomeSection.Hub) { goToSection(HomeSection.Hub) }

                SettingsSidebar(
                    drawerState = drawerState,
                    onClose = { scope.launch { drawerState.close() } },
                    settings = {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { scope.launch { drawerState.close() } }
                        )
                    },
                ) {
                    HomeScreen(
                        section = section,
                        onSelectSection = goToSection,
                        onOpenSettings = { scope.launch { drawerState.open() } },
                        viewModel = homeViewModel
                    )
                }
            }

            // Kept so an in-flight manual navigation (or a restored state that still names Settings)
            // resolves to something rather than falling through the `when`. It is no longer reachable
            // from the UI — nothing calls navigateTo(Settings) — and comes out once the panel has run
            // on a device.
            AppScreen.Settings -> {
                BackHandler { appNavViewModel.navigateBack() }
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { appNavViewModel.navigateBack() }
                )
            }
        }
    }
}

// -------- Private helpers

/**
 * Maps an [OnboardingStatus.Status] snapshot to the [AppScreen] that should be visible.
 *
 * The logic is intentionally linear:
 *  1. Disclaimer first — the user must accept before anything else is shown.
 *  2. Permissions next — every required permission must be granted.
 *  3. Wizard next — the one-time setup wizard must be completed.
 *  4. Home last — the default landing screen once everything is set up.
 *
 * Settings is intentionally NOT produced here; it is reached only via manual navigation
 * (see [AppNavigationViewModel.navigateTo]).
 *
 * Relying on [OnboardingStatus.Status] (part of the `StateFlow`) instead of reading preferences
 * directly ensures that each acceptance/grant emits a new value through the flow, which
 * triggers a refresh (recompose) in [AppNavigationScreen] and advances the user automatically.
 *
 * @param status The latest snapshot emitted by [AppNavigationViewModel.onboardingStatus].
 * @return The [AppScreen] that matches the user's current setup progress.
 */
private fun resolveScreen(status: OnboardingStatus.Status): AppScreen {
    return when {
        !status.disclaimerAccepted -> AppScreen.Disclaimer
        !status.isComplete()       -> AppScreen.Permissions
        !status.wizardCompleted    -> AppScreen.Wizard
        else                       -> AppScreen.Home
    }
}
