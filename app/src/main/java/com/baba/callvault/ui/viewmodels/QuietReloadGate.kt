/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

/**
 * Whether a reload nobody asked for may run now (review R50). A background Drive sweep stamps catalog rows; Home
 * used to answer each burst with a full recordings listing and a Home folder check while no screen was showing
 * ("Listed N recordings" twice, 4 s apart, app in the background). While the UI is hidden those are skipped: the
 * next resume runs [HomeViewModel.refresh] and [HomeViewModel.onHomeVisible], which do the same work for a user
 * who can see the result. An explicit refresh (a delete, an import) never goes through this gate.
 */
class QuietReloadGate {
    @Volatile
    private var uiVisible = true

    fun setUiVisible(visible: Boolean) { uiVisible = visible }

    fun mayReloadQuietly(): Boolean = uiVisible
}
