package com.baba.callvault.dialer

import android.content.Context
import android.telecom.TelecomManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.utils.AppLogger

/** Pure shell-command strings (unit-testable). */
object DialerCommands {
    fun setDefault(pkg: String) = "cmd telecom set-default-dialer $pkg"
    fun grantCallPhone(pkg: String) = "pm grant $pkg android.permission.CALL_PHONE"
    fun restore(priorPkg: String) = "cmd telecom set-default-dialer $priorPkg"
    /** Combines set-default + grant into ONE shell invocation (avoids a second ADB stream on flaky links). */
    fun setDefaultAndGrant(pkg: String) = "cmd telecom set-default-dialer $pkg ; pm grant $pkg android.permission.CALL_PHONE"
}

/**
 * Forces CallVault as the Telecom default dialer via the embedded ADB shell, because OEMs (OxygenOS)
 * grant ROLE_DIALER but don't propagate it into Telecom's default-dialer cache. Blocking; call OFF the
 * main thread.
 */
class DialerDefaultEnforcer(
    private val context: Context,
    private val prefs: AppPreferences,
    private val roleController: DialerRoleController,
) {
    private val pkg = context.packageName
    private val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    /** Make CallVault the Telecom default dialer + grant CALL_PHONE. Returns true if it actually became default. */
    fun enforce(): Boolean {
        if (roleController.isDefaultDialer()) return true
        if (!AdbShell.ensureConnected(context)) {
            AppLogger.w(TAG, "enforce() skipped: ADB connection unavailable")
            return false
        }
        // Remember who held it so we can restore on relinquish (only if it wasn't already us).
        @Suppress("DEPRECATION")
        val current = telecom?.defaultDialerPackage
        if (current != null && current != pkg && prefs.getPriorDefaultDialer() == null) {
            prefs.setPriorDefaultDialer(current)
        }
        AdbShell.runShellCommand(context, DialerCommands.setDefaultAndGrant(pkg))
        val ok = roleController.isDefaultDialer()
        AppLogger.i(TAG, "enforce() default-dialer now=${if (ok) pkg else current} ok=$ok")
        return ok
    }

    /** Restore the previously-stored default dialer (best-effort) and clear the stored value. */
    fun relinquish() {
        val prior = prefs.getPriorDefaultDialer()
            ?: @Suppress("DEPRECATION") telecom?.systemDialerPackage
        if (prior != null && prior != pkg) {
            if (!AdbShell.ensureConnected(context)) {
                AppLogger.w(TAG, "relinquish() skipped restore: ADB connection unavailable")
                return
            }
            AdbShell.runShellCommand(context, DialerCommands.restore(prior))
        }
        prefs.setPriorDefaultDialer(null)
        AppLogger.i(TAG, "relinquish() restored default dialer to $prior")
    }

    private companion object { const val TAG = "CV:DialerEnforcer" }
}
