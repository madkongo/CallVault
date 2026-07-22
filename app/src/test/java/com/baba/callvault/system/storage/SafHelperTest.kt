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
 * Verifies [SafHelper.isCloudFolder] — the guard that keeps a cloud/synced document provider from being
 * used as the live recording-capture folder (they reject "rw" and report length asynchronously, which
 * silently breaks capture; see the Google Drive field report).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafHelperTest {

    @Test
    fun `google drive tree uri is detected as cloud`() {
        val uri = ("content://com.google.android.apps.docs.storage/tree/" +
            "acc%3D1%3Bdoc%3Dencoded%3Dabc").toUri()
        assertTrue(SafHelper.isCloudFolder(uri))
    }

    @Test
    fun `google drive legacy authority is detected as cloud`() {
        assertTrue(SafHelper.isCloudFolder("content://com.google.android.apps.docs.storage.legacy/tree/x".toUri()))
    }

    @Test
    fun `onedrive and dropbox authorities are detected as cloud`() {
        assertTrue(SafHelper.isCloudFolder("content://com.microsoft.skydrive.content.StorageAccessProvider/tree/x".toUri()))
        assertTrue(SafHelper.isCloudFolder("content://com.dropbox.product.android.dbapp.document_provider.documents/tree/x".toUri()))
    }

    @Test
    fun `on-device external storage folder is NOT cloud`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FCallVault".toUri()
        assertFalse(SafHelper.isCloudFolder(uri))
    }

    @Test
    fun `null uri is not cloud`() {
        assertFalse(SafHelper.isCloudFolder(null))
    }
}
