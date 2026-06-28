/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

/**
 * Pure reconciliation logic: derives the effective dialer-mode state from the user preference
 * and the actual RoleManager role status. Keeping this as a plain object makes it trivially
 * testable without Android instrumentation.
 */
object DialerModeState {

    /**
     * Dialer mode is only active when the user has opted in **and** the app still holds the role.
     * If the role was revoked behind the user's back, effective() returns false even though the
     * preference is still ON — the banner (see [shouldShowRoleLostBanner]) surfaces that gap.
     */
    fun effective(prefOn: Boolean, roleHeld: Boolean): Boolean = prefOn && roleHeld

    /**
     * Returns true when the user's preference is ON but the role is no longer held, signalling
     * that a role-loss banner should be shown so the user can re-grant the role.
     */
    fun shouldShowRoleLostBanner(prefOn: Boolean, roleHeld: Boolean): Boolean = prefOn && !roleHeld
}
