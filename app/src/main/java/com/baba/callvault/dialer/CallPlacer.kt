/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

class CallPlacer(private val context: Context) {

    fun place(number: String) {
        if (!isDialable(number)) return
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val uri = Uri.fromParts("tel", normalize(number), null)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            tm.placeCall(uri, null) // emergency numbers handled by the platform
        }
    }

    companion object {
        fun isDialable(input: String): Boolean = normalize(input).isNotEmpty()

        fun normalize(input: String): String =
            input.trim().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }
}
