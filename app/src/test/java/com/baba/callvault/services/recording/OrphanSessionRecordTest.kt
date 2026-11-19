/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Gate review item 3: the record of which file the recorder is writing, across a late app-call end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrphanSessionRecordTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun recorded() = app.getSharedPreferences("orphan_session", Context.MODE_PRIVATE).getString("session_staging", null)

    @Test
    fun `a late app-call end keeps the phone call's session record`() {
        OrphanRecorderSession.noteSessionStarted(app, File("/s/carrier.tmp"))
        OrphanRecorderSession.noteSessionEnded(app, File("/s/voip.tmp"))
        assertEquals("/s/carrier.tmp", recorded())
    }

    @Test
    fun `its own end removes it`() {
        OrphanRecorderSession.noteSessionStarted(app, File("/s/carrier.tmp"))
        OrphanRecorderSession.noteSessionEnded(app, File("/s/carrier.tmp"))
        assertNull(recorded())
    }
}
