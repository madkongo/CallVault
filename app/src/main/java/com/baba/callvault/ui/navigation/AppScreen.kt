/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

/**
 * The top-level destinations the app router can show. The router (AppNavigationScreen) renders one
 * of these at a time.
 *
 * [Disclaimer], [Permissions], [Wizard] and [Home] form the linear onboarding/landing flow resolved
 * from onboarding state. [Settings] is reached only via manual in-app navigation (there is no Jetpack
 * NavHost), driven by [com.baba.callvault.ui.viewmodels.AppNavigationViewModel.navigateTo].
 */
enum class AppScreen {
    /** The user has not yet accepted the legal disclaimer. */
    Disclaimer,

    /** One or more required permissions are still missing. */
    Permissions,

    /** Permissions are granted but the one-time setup wizard has not been completed. */
    Wizard,

    /** Setup is complete. The default landing screen. */
    Home,

    /** Settings. Reachable only via manual navigation from [Home]. */
    Settings
}
