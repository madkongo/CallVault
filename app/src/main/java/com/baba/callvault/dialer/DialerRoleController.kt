package com.baba.callvault.dialer

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * Wraps the default-dialer (ROLE_DIALER) role.
 *
 * - "Am I the default dialer?" is answered by [TelecomManager.getDefaultDialerPackage], NOT by
 *   [RoleManager.isRoleHeld]. The Telecom default-dialer package is what actually determines whether
 *   CallVault's InCallService is bound and receives calls; on some OEMs (observed on OxygenOS)
 *   `isRoleHeld` can report `true` while Telecom still binds another app's dialer, which would make
 *   us wrongly defer call detection to a Telecom path that never fires.
 * - Requesting the role still uses [RoleManager.createRequestRoleIntent] (the supported request API).
 */
class DialerRoleController(private val context: Context) {

    private val roleManager: RoleManager? =
        context.getSystemService(Context.ROLE_SERVICE) as? RoleManager

    private val telecomManager: TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    /** True only when CallVault is the *actual* Telecom default dialer (so our InCallService is bound). */
    fun isDefaultDialer(): Boolean {
        val tm = telecomManager ?: return false
        @Suppress("DEPRECATION") // getDefaultDialerPackage is the authoritative source for InCallService binding.
        return tm.defaultDialerPackage == context.packageName
    }

    /**
     * Intent to ask the user to make CallVault the default phone app, or null if the role is
     * unavailable or CallVault is *already* the real default dialer.
     *
     * "Already holding it" is judged by [isDefaultDialer] (Telecom), NOT by [RoleManager.isRoleHeld]:
     * on OxygenOS isRoleHeld can be stuck `true` while Telecom binds another dialer, which previously
     * made this return null forever — so the role-lost banner and the enable toggle did nothing.
     */
    fun requestRoleIntent(): Intent? {
        val rm = roleManager ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) return null
        if (isDefaultDialer()) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }

    /** We cannot drop the role programmatically; send the user to default-apps settings. */
    fun releaseRoleHint(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}
