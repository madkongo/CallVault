package com.baba.callvault.dialer

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Wraps RoleManager for the default-dialer (ROLE_DIALER) role. Truth = isRoleHeld, never a preference. */
class DialerRoleController(private val context: Context) {

    private val roleManager: RoleManager? =
        context.getSystemService(Context.ROLE_SERVICE) as? RoleManager

    fun isDefaultDialer(): Boolean {
        val rm = roleManager ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_DIALER) && rm.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    /** Intent to ask the user to make CallVault the default phone app, or null if already held/unavailable. */
    fun requestRoleIntent(): Intent? {
        val rm = roleManager ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER) || rm.isRoleHeld(RoleManager.ROLE_DIALER)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }

    /** We cannot drop the role programmatically; send the user to default-apps settings. */
    fun releaseRoleHint(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}
