/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Shows/hides the "CallVault Dialer" launcher icon (an activity-alias) with dialer mode. */
object DialerLauncherIcon {
    fun setEnabled(context: Context, enabled: Boolean) {
        val alias = ComponentName(context.packageName, "com.baba.callvault.dialer.DialerLauncherAlias")
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
    }
}
