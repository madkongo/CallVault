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
