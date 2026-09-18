/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Working out where a Shizuku manager keeps its starter, without ever knowing its package name.
 *
 * The real paths here are the OP9's, read off the device on 2026-09-18, where running
 * `…/lib/arm64/libshizuku.so` printed `info: shizuku_server pid is 23611`.
 */
class ShizukuStarterPathsTest {

    companion object {
        const val NATIVE = "/data/app/~~Q5MOmEfZPOmDzrjOTphCmg==/moe.shizuku.privileged.api-SfqBV3kxLpf8lKBq393hLg==/lib/arm64"
        const val APK = "/data/app/~~Q5MOmEfZPOmDzrjOTphCmg==/moe.shizuku.privileged.api-SfqBV3kxLpf8lKBq393hLg==/base.apk"
        const val INSTALL = "/data/app/~~Q5MOmEfZPOmDzrjOTphCmg==/moe.shizuku.privileged.api-SfqBV3kxLpf8lKBq393hLg=="
    }

    @Test
    fun `the installed ABI comes first, because Android already chose it`() {
        val paths = ShizukuStarterPaths.candidates(NATIVE, APK, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("$NATIVE/libshizuku.so", paths.first())
    }

    @Test
    fun `several ABIs each get a path when only the APK location is known`() {
        val paths = ShizukuStarterPaths.candidates(null, APK, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        // armeabi-v7a and armeabi share one directory; the duplicate must not be probed twice.
        assertEquals(
            listOf("$INSTALL/lib/arm64/libshizuku.so", "$INSTALL/lib/arm/libshizuku.so"),
            paths,
        )
    }

    @Test
    fun `a pm path line is accepted as it comes off the shell`() {
        val paths = ShizukuStarterPaths.candidates(null, "package:$APK", listOf("arm64-v8a"))
        assertEquals(listOf("$INSTALL/lib/arm64/libshizuku.so"), paths)
    }

    @Test
    fun `nothing usable means no candidate, never a guess`() {
        // A fork whose layout the package manager will not describe has to fail into "could not start
        // it" and a notification, not into CallVault running an invented path on a privileged shell.
        assertEquals(emptyList<String>(), ShizukuStarterPaths.candidates(null, null, listOf("arm64-v8a")))
        assertEquals(emptyList<String>(), ShizukuStarterPaths.candidates("", "   ", listOf("arm64-v8a")))
        assertEquals(emptyList<String>(), ShizukuStarterPaths.candidates(null, "base.apk", listOf("arm64-v8a")))
        // An ABI nobody ships a directory name for is skipped rather than guessed at.
        assertEquals(emptyList<String>(), ShizukuStarterPaths.candidates(null, APK, listOf("riscv64")))
    }

    @Test
    fun `a trailing slash on the library directory does not double up`() {
        assertEquals(
            listOf("$NATIVE/libshizuku.so"),
            ShizukuStarterPaths.candidates("$NATIVE/", null, emptyList()),
        )
    }

    @Test
    fun `the same path is never probed twice`() {
        val paths = ShizukuStarterPaths.candidates(NATIVE, APK, listOf("arm64-v8a"))
        assertEquals(paths.distinct(), paths)
        assertEquals(1, paths.size)
    }

    @Test
    fun `a path that could break out of the shell quoting is refused`() {
        // The hazard: the command is built as '<path>', so a quote would close it and everything after
        // would run as its own command — on a privileged shell, from a string another app controls.
        assertTrue(ShizukuStarterPaths.isShellSafe("$NATIVE/libshizuku.so"))
        assertFalse(ShizukuStarterPaths.isShellSafe("/data/app/x'; rm -rf /data; echo '/lib/arm64/libshizuku.so"))
        assertFalse(ShizukuStarterPaths.isShellSafe("/data/app/x\nreboot"))
        assertFalse(ShizukuStarterPaths.isShellSafe("lib/arm64/libshizuku.so"))
        assertFalse(ShizukuStarterPaths.isShellSafe(""))
    }
}
