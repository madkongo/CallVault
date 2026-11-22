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
 * ("Listed N recordings" twice, 4 s apart, app in the background). While the UI is hidden those are skipped.
 *
 * Healing: the next ON_RESUME always runs [HomeViewModel.refresh], which always ends in `startListPass` (or queues
 * one behind the pass in flight), and [HomeViewModel.onHomeVisible] re-looks at the folder, so a user who can see
 * the result gets it then. The catch-up pass queued behind a running pass is also dropped while hidden, and that
 * pass may have merged an explicit refresh (a delete) — resume heals that too. [skipped] returns true only for the
 * first skip of each hidden spell, so the log gets one line per spell, not one per catalog write.
 */
class QuietReloadGate {
    @Volatile
    private var uiVisible = true
    private var skipLogged = false

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) synchronized(this) { skipLogged = false }
    }

    fun mayReloadQuietly(): Boolean = uiVisible

    /** True the first time it is called in a hidden spell: the caller logs then and stays quiet after. */
    @Synchronized
    fun shouldLogSkip(): Boolean = (!skipLogged).also { skipLogged = true }
}
