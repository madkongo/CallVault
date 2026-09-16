/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import com.baba.callvault.integrations.adb.ShellGrantGate.OemGate
import com.baba.callvault.integrations.adb.ShellGrantGate.ShellGrantState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The OEM gate that refuses `pm grant` from the shell — see docs/dev-notes/2026-09-16-oem-adb-restrictions-and-onboarding-check.md. */
class ShellGrantGateTest {

    // ---- The OPPO property (measured on the OP9, 2026-09-16: toggle ON -> "false", OFF -> "true") ----

    @Test
    fun `oppo property true means the shell is blocked`() {
        assertEquals(ShellGrantState.BLOCKED, ShellGrantGate.fromOemProperty("true"))
    }

    @Test
    fun `oppo property false means the shell may grant`() {
        assertEquals(ShellGrantState.ALLOWED, ShellGrantGate.fromOemProperty("false"))
    }

    @Test
    fun `no property at all says nothing - most phones have no gate`() {
        assertEquals(ShellGrantState.UNKNOWN, ShellGrantGate.fromOemProperty(""))
        assertEquals(ShellGrantState.UNKNOWN, ShellGrantGate.fromOemProperty(null))
        assertEquals(ShellGrantState.UNKNOWN, ShellGrantGate.fromOemProperty("something else"))
    }

    // ---- The portable probe: attempt the grant, then look at what actually happened ----

    @Test
    fun `a grant that landed proves the shell may grant, whatever it printed`() {
        assertEquals(ShellGrantState.ALLOWED, ShellGrantGate.fromGrantAttempt(output = "", permissionHeldAfter = true))
        assertEquals(
            ShellGrantState.ALLOWED,
            ShellGrantGate.fromGrantAttempt(output = "odd OEM noise", permissionHeldAfter = true),
        )
    }

    @Test
    fun `the security exception the gate raises is read as blocked`() {
        val oppo = "Exception occurred while executing 'grant':\n" +
            "java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current process has " +
            "android.permission.GRANT_RUNTIME_PERMISSIONS."
        assertEquals(ShellGrantState.BLOCKED, ShellGrantGate.fromGrantAttempt(oppo, permissionHeldAfter = false))
    }

    @Test
    fun `a silent failure is not called blocked - Xiaomi reports success and grants nothing`() {
        // HyperOS 3 report: `pm grant` prints nothing and the permission is still missing. We cannot tell a
        // gate from a broken connection here, so we must not name a cause we have not established.
        assertEquals(ShellGrantState.UNKNOWN, ShellGrantGate.fromGrantAttempt("", permissionHeldAfter = false))
    }

    @Test
    fun `a stream that never ran is unknown, not blocked`() {
        assertEquals(
            ShellGrantState.UNKNOWN,
            ShellGrantGate.fromGrantAttempt("Stream closed", permissionHeldAfter = false),
        )
    }

    // ---- Which phone's wording to show ----

    @Test
    fun `an oppo family phone is recognised by its own property`() {
        assertEquals(OemGate.OPPO, ShellGrantGate.oemGate(manufacturer = "OnePlus", oemProperty = "true", miuiProperty = ""))
        assertEquals(OemGate.OPPO, ShellGrantGate.oemGate(manufacturer = "realme", oemProperty = "false", miuiProperty = ""))
    }

    @Test
    fun `an oppo family phone is still recognised when the property is missing`() {
        assertEquals(OemGate.OPPO, ShellGrantGate.oemGate(manufacturer = "OPPO", oemProperty = "", miuiProperty = ""))
    }

    @Test
    fun `a xiaomi phone gets xiaomi wording`() {
        assertEquals(OemGate.XIAOMI, ShellGrantGate.oemGate(manufacturer = "Xiaomi", oemProperty = "", miuiProperty = "1"))
        assertEquals(OemGate.XIAOMI, ShellGrantGate.oemGate(manufacturer = "POCO", oemProperty = "", miuiProperty = ""))
    }

    @Test
    fun `anything else gets the general wording`() {
        assertEquals(OemGate.OTHER, ShellGrantGate.oemGate(manufacturer = "samsung", oemProperty = "", miuiProperty = ""))
        assertEquals(OemGate.OTHER, ShellGrantGate.oemGate(manufacturer = "Google", oemProperty = "", miuiProperty = ""))
        assertEquals(OemGate.OTHER, ShellGrantGate.oemGate(manufacturer = "", oemProperty = "", miuiProperty = ""))
    }

    @Test
    fun `vivo and meizu are named so their own wording can be added later`() {
        assertEquals(OemGate.VIVO, ShellGrantGate.oemGate(manufacturer = "vivo", oemProperty = "", miuiProperty = ""))
        assertEquals(OemGate.MEIZU, ShellGrantGate.oemGate(manufacturer = "Meizu", oemProperty = "", miuiProperty = ""))
    }

    // ---- What the user is told ----

    @Test
    fun `only a blocked state warrants telling the user to go and change a switch`() {
        assertEquals(true, ShellGrantGate.shouldAdvise(ShellGrantState.BLOCKED, permissionHeld = false))
        // Already granted: the switch no longer matters, so do not nag.
        assertEquals(false, ShellGrantGate.shouldAdvise(ShellGrantState.BLOCKED, permissionHeld = true))
        assertEquals(false, ShellGrantGate.shouldAdvise(ShellGrantState.ALLOWED, permissionHeld = false))
        assertEquals(false, ShellGrantGate.shouldAdvise(ShellGrantState.UNKNOWN, permissionHeld = false))
    }
}
