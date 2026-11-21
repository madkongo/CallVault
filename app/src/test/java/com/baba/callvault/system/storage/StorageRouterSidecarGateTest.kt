/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43: the sidecar copy step walked the whole recording folder looking for a file that cannot exist
 * when the setting that writes it is off (the default). It must ask the setting first.
 */
class StorageRouterSidecarGateTest {

    @Test
    fun `details json is wanted only when the details file setting is on`() {
        assertFalse(StorageRouter.sidecarMayExist("call.json", detailsFileOn = false, transcriptSidecarOn = true))
        assertTrue(StorageRouter.sidecarMayExist("call.json", detailsFileOn = true, transcriptSidecarOn = false))
    }

    @Test
    fun `transcript md is wanted only when the transcript sidecar setting is on`() {
        assertFalse(StorageRouter.sidecarMayExist("call.md", detailsFileOn = true, transcriptSidecarOn = false))
        assertTrue(StorageRouter.sidecarMayExist("call.MD", detailsFileOn = false, transcriptSidecarOn = true))
    }

    @Test
    fun `both settings off means no sidecar can exist`() {
        assertFalse(StorageRouter.sidecarMayExist("call.json", detailsFileOn = false, transcriptSidecarOn = false))
        assertFalse(StorageRouter.sidecarMayExist("call.md", detailsFileOn = false, transcriptSidecarOn = false))
    }
}
