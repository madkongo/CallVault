/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import androidx.core.net.toUri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Drive folder must never be the recording folder (or inside it, or around it). A Xiaomi user picked
 * Alarms for both on 2.4.9 with "Drive only": the copy step found each recording "already in Drive" — it
 * was looking at the file itself — and deleted the only copy (2026-11-04).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderOverlapTest {

    private val ext = "content://com.android.externalstorage.documents"
    private fun tree(docId: String) = "$ext/tree/${android.net.Uri.encode(docId)}".toUri()
    private fun doc(treeId: String, docId: String) =
        "$ext/tree/${android.net.Uri.encode(treeId)}/document/${android.net.Uri.encode(docId)}".toUri()

    @Test
    fun `the same folder overlaps`() {
        assertTrue(FolderOverlap.overlaps(tree("primary:Alarms"), tree("primary:Alarms")))
    }

    @Test
    fun `a folder inside the other overlaps, either way round`() {
        assertTrue(FolderOverlap.overlaps(tree("primary:Alarms"), tree("primary:Alarms/Backup")))
        assertTrue(FolderOverlap.overlaps(tree("primary:Alarms/Backup"), tree("primary:Alarms")))
    }

    @Test
    fun `the storage root holds every folder on it`() {
        assertTrue(FolderOverlap.overlaps(tree("primary:"), tree("primary:Recordings")))
    }

    @Test
    fun `a sibling whose name starts the same does not overlap`() {
        assertFalse(FolderOverlap.overlaps(tree("primary:Alarms"), tree("primary:AlarmsOld")))
    }

    @Test
    fun `different folders do not overlap`() {
        assertFalse(FolderOverlap.overlaps(tree("primary:Recordings"), tree("primary:Music")))
    }

    @Test
    fun `an SD card folder with the same path does not overlap the phone's`() {
        assertFalse(FolderOverlap.overlaps(tree("primary:Recordings"), tree("1234-ABCD:Recordings")))
    }

    @Test
    fun `a Drive folder never overlaps a phone folder`() {
        val drive = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3Dabc".toUri()
        assertFalse(FolderOverlap.overlaps(tree("primary:Recordings"), drive))
    }

    @Test
    fun `an unset folder overlaps nothing`() {
        assertFalse(FolderOverlap.overlaps(null, tree("primary:Alarms")))
        assertFalse(FolderOverlap.overlaps(tree("primary:Alarms"), null))
    }

    @Test
    fun `a recording found again through the same folder is the same document`() {
        val src = doc("primary:Alarms", "primary:Alarms/call.m4a")
        val found = doc("primary:Alarms", "primary:Alarms/call.m4a")
        assertTrue(FolderOverlap.isSameDocument(src, found))
    }

    @Test
    fun `the same file reached through a parent folder is still the same document`() {
        val src = doc("primary:Alarms", "primary:Alarms/call.m4a")
        val found = doc("primary:", "primary:Alarms/call.m4a")
        assertTrue(FolderOverlap.isSameDocument(src, found))
    }

    @Test
    fun `a real Drive copy is a different document`() {
        val src = doc("primary:Alarms", "primary:Alarms/call.m4a")
        val drive = ("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3Dabc" +
            "/document/acc%3D1%3Bdoc%3Dencoded%3Dxyz").toUri()
        assertFalse(FolderOverlap.isSameDocument(src, drive))
    }

    @Test
    fun `a file with the same name in another phone folder is a different document`() {
        val src = doc("primary:Alarms", "primary:Alarms/call.m4a")
        val other = doc("primary:Backup", "primary:Backup/call.m4a")
        assertFalse(FolderOverlap.isSameDocument(src, other))
    }

    @Test
    fun `an unreadable uri is treated as the same document, so nothing is deleted`() {
        assertTrue(FolderOverlap.isSameDocument("$ext/not-a-document".toUri(), doc("primary:A", "primary:A/c.m4a")))
    }
}
