/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.net.Uri
import com.baba.callvault.system.storage.FolderOverlap.FileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C55: the same directory reached through two providers must never let the device copy be deleted as "already in
 * Drive". The safe order: tell the files apart BEFORE the delete, and keep the device copy on any doubt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderOverlapSameFileTest {

    private val device = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAlarms/document/primary%3AAlarms%2Fa.ogg")
    private val viaOtherLocalProvider = Uri.parse("content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FAlarms/document/raw%3A%2Fstorage%2Femulated%2F0%2FAlarms%2Fa.ogg")
    private val drive = Uri.parse("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dx")

    @Test
    fun `the same inode through two local providers is the same file`() {
        assertTrue(FolderOverlap.isSameFile(device, viaOtherLocalProvider) { FileId(7, 42) })
    }

    @Test
    fun `different inodes through two local providers are different files`() {
        val ids = mapOf(device to FileId(7, 42), viaOtherLocalProvider to FileId(7, 43))
        assertFalse(FolderOverlap.isSameFile(device, viaOtherLocalProvider) { ids[it] })
    }

    @Test
    fun `any failure to read a file id keeps the device copy`() {
        assertTrue(FolderOverlap.isSameFile(device, viaOtherLocalProvider) { null })
        assertTrue(FolderOverlap.isSameFile(device, viaOtherLocalProvider) { if (it == device) FileId(1, 1) else null })
    }

    @Test
    fun `a cloud provider is never opened and is never the same file`() {
        val opened = mutableListOf<Uri>()

        val same = FolderOverlap.isSameFile(device, drive) { opened += it; FileId(1, 1) }

        assertFalse(same)
        assertEquals(emptyList<Uri>(), opened)
    }

    @Test
    fun `the same document id on one provider is still caught without opening anything`() {
        val opened = mutableListOf<Uri>()
        assertTrue(FolderOverlap.isSameFile(device, device) { opened += it; null })
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `two different documents on one provider are different without opening anything`() {
        val other = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AX/document/primary%3AX%2Fb.ogg")
        val opened = mutableListOf<Uri>()
        assertFalse(FolderOverlap.isSameFile(device, other) { opened += it; null })
        assertTrue(opened.isEmpty())
    }
}
