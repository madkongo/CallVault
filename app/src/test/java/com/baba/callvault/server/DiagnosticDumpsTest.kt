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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which diagnostic dumps the privileged daemon is willing to run.
 *
 * This is a **whitelist, not a command runner**, and the distinction is the whole point. The daemon
 * runs as the shell user; a method that took a command string from the app process and executed it
 * would hand shell-uid execution to anything that could reach the binder. So the app names a dump and
 * the daemon decides what that means.
 *
 * The one place a caller's value reaches a command line is the logcat buffer size on restore, which
 * is why most of the tests below are about that single argument.
 */
class DiagnosticDumpsTest {

    @Test
    fun `each known dump maps to a command`() {
        listOf(
            "logcat_size", "logcat_grow", "logcat_dump", "dumpsys_audio", "appops_mic", "appops_all", "processes",
            "setting_adb_enabled", "setting_dev_options",
        )
            .forEach { key ->
                assertTrue("$key must be runnable", DiagnosticDumps.commandFor(key, null) != null)
            }
    }

    @Test
    fun `an unknown dump is refused`() {
        assertNull(DiagnosticDumps.commandFor("rm_rf", null))
        assertNull(DiagnosticDumps.commandFor("", null))
    }

    @Test
    fun `restoring the logcat size accepts a plain size`() {
        val command = DiagnosticDumps.commandFor("logcat_restore", "256K")
        assertTrue("must carry the size", command!!.joinToString(" ").contains("256K"))
    }

    @Test
    fun `restoring the logcat size refuses anything that is not a size`() {
        // The only caller-supplied value that reaches a command line. A shell metacharacter here
        // would run as the shell user, so nothing but digits and a unit suffix is allowed through.
        listOf(
            "256K; rm -rf /data",
            "256K && id",
            "\$(id)",
            "`id`",
            "256K | sh",
            "../../etc",
            "",
            "abc",
            "-1",
        ).forEach { hostile ->
            assertNull("must refuse '$hostile'", DiagnosticDumps.commandFor("logcat_restore", hostile))
        }
    }

    @Test
    fun `restoring without a size is refused rather than defaulted`() {
        // Guessing a buffer size would silently resize the user's logcat ring to something they
        // never had.
        assertNull(DiagnosticDumps.commandFor("logcat_restore", null))
    }

    @Test
    fun `dumps that need no shell are run directly, not through one`() {
        // A pipeline needs sh -c; a plain dump does not, and running one through a shell only widens
        // what a mistake in this table could do.
        val direct = DiagnosticDumps.commandFor("dumpsys_audio", null)!!
        assertEquals("/system/bin/dumpsys", direct.first())

        val piped = DiagnosticDumps.commandFor("appops_mic", null)!!
        assertEquals("/system/bin/sh", piped.first())
    }

    @Test
    fun `every command is an absolute path`() {
        // A relative name is resolved against PATH, which is not ours to trust in a privileged
        // process. Flagged by CodeQL on this codebase before.
        listOf("logcat_size", "logcat_grow", "logcat_dump", "dumpsys_audio", "appops_mic", "appops_all", "processes")
            .forEach { key ->
                val command = DiagnosticDumps.commandFor(key, null)!!
                assertTrue("$key must exec an absolute path, got ${command.first()}", command.first().startsWith("/"))
            }
    }

    @Test
    fun `the settings reads are read-only, fixed, and take no caller input`() {
        // These exist because Android 17 lies to the app process about these two settings and the daemon
        // is uid 2000, which the platform exempts. The value of the seam is entirely in it being a
        // *fixed* read: the key names the setting, so nothing crossing the binder can choose one.
        listOf("setting_adb_enabled" to "adb_enabled", "setting_dev_options" to "development_settings_enabled")
            .forEach { (key, name) ->
                val command = DiagnosticDumps.commandFor(key, null)!!
                assertTrue("$key must read, never write", command.contains("get") && !command.contains("put"))
                assertTrue("$key must name its own setting", command.contains(name))
                // An argument must not be able to redirect it -- only logcat_restore takes one at all.
                assertTrue("$key must ignore any argument", DiagnosticDumps.commandFor(key, "put global x 1")!!.contentEquals(command))
            }
    }
}
