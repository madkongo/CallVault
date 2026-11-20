/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Review N21 rework (3a): the shell Application's attribution carries the process's real uid. A Shizuku server
 * started with root runs its user service — our recorder host — as uid 0, and that host reaches
 * [ShellApplication.install] through the same AudioRecord paths. uid 0 with the shell package is a pairing nothing owns;
 * without a package the AudioRecord constructor names it "uid:0", as it does for a root host today.
 */
class ShellApplicationAttributionTest {

    @Test
    fun the_shell_uid_presents_the_shell_package() {
        assertEquals("com.android.shell", ShellApplication.attributionPackageFor(2000))
    }

    @Test
    fun root_presents_no_package() {
        assertNull(ShellApplication.attributionPackageFor(0))
    }

    @Test
    fun any_other_uid_presents_no_package() {
        assertNull(ShellApplication.attributionPackageFor(1000))
    }
}

/**
 * The Honor path (#53, `startWithRepair`) keeps the attribution it shipped with: uid 2000 + the shell package, whatever
 * the process uid. Only the vivo repair path (review N21) asks for the process's real uid. Owner rule: a phone that
 * works today behaves exactly as today.
 */
class ShellApplicationAttributionModeTest {

    @Test
    fun honor_path_keeps_shell_uid_and_package_even_as_root() {
        assertEquals(2000 to "com.android.shell", ShellApplication.attributionFor(processUid = 0, toProcessUid = false))
    }

    @Test
    fun vivo_path_as_root_presents_uid_0_without_package() {
        assertEquals(0 to null, ShellApplication.attributionFor(processUid = 0, toProcessUid = true))
    }

    @Test
    fun vivo_path_as_shell_presents_the_shell() {
        assertEquals(2000 to "com.android.shell", ShellApplication.attributionFor(processUid = 2000, toProcessUid = true))
    }
}
