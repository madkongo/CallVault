/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * OnePlus 9 Pro, 2026-11-20: the recording folder renamed away, and the phone's own storage provider answered with a
 * null cursor, not "no such file", so the loss waited out the 10-minute transient grace. A direct look at the path
 * settles it, but only when the look itself can be trusted.
 */
class FolderPathLookTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rootWith(vararg dirs: String): File = tmp.newFolder("storage").also { root ->
        dirs.forEach { File(root, it).mkdirs() }
    }

    @Test
    fun `a folder on the phone's own storage maps to its path, anything else to nothing`() {
        assertEquals("Recordings", FolderPathLook.relativePathOnPrimary("primary:Recordings"))
        assertEquals("Music/Calls", FolderPathLook.relativePathOnPrimary("primary:Music/Calls"))
        assertNull("the storage root itself cannot go missing", FolderPathLook.relativePathOnPrimary("primary:"))
        assertNull("an SD card is left to its own mount state", FolderPathLook.relativePathOnPrimary("1A2B-3C4D:Recordings"))
        assertNull(FolderPathLook.relativePathOnPrimary("primary:../etc"))
        assertNull(FolderPathLook.relativePathOnPrimary("primary:Music/../../x"))
        assertNull("other apps' folders are hidden from us, so absence proves nothing", FolderPathLook.relativePathOnPrimary("primary:Android/data/x"))
        assertNull(FolderPathLook.relativePathOnPrimary("primary:android/media"))
        assertNull(FolderPathLook.relativePathOnPrimary("garbage"))
    }

    @Test
    fun `a folder that is not there, beside folders that are seen, is absent`() {
        val result = FolderPathLook.look(rootWith("Download", "DCIM"), "Recordings")
        assertEquals(PathLook.ABSENT, result.look)
        assertTrue(result.evidence, result.evidence.contains("Download"))
    }

    @Test
    fun `a nested folder whose parent is gone too is absent`() {
        assertEquals(PathLook.ABSENT, FolderPathLook.look(rootWith("Download"), "Music/Calls").look)
    }

    @Test
    fun `a folder that is there exists, so a provider that gave no answer was only slow`() {
        assertEquals(PathLook.EXISTS, FolderPathLook.look(rootWith("Download", "Recordings"), "Recordings").look)
    }

    @Test
    fun `a file where the folder should be is not the folder`() {
        val root = rootWith("Download")
        File(root, "Recordings").writeText("x")
        assertEquals(PathLook.ABSENT, FolderPathLook.look(root, "Recordings").look)
    }

    @Test
    fun `when no standard folder is seen either, the look cannot be trusted`() {
        val result = FolderPathLook.look(rootWith(), "Recordings")
        assertEquals(PathLook.CANNOT_TELL, result.look)
        assertTrue(result.evidence, result.evidence.isNotBlank())
    }

    @Test
    fun `a storage root that cannot be seen cannot tell`() {
        assertEquals(PathLook.CANNOT_TELL, FolderPathLook.look(File(tmp.root, "no-such-root"), "Recordings").look)
    }
}
