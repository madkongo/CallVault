/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonStereoTest {

    @Test
    fun `the choice is sent both ways, so a start never inherits an old one`() {
        val sent = mutableListOf<Boolean>()
        assertTrue(DaemonStereo.request({ sent += it }, stereo = true) {})
        assertTrue(DaemonStereo.request({ sent += it }, stereo = false) {})
        assertEquals(listOf(true, false), sent)
    }

    @Test
    fun `an older recorder without the method never fails the call`() {
        val warnings = mutableListOf<String>()
        val reached = DaemonStereo.request({ throw RemoteException("Method setEncodeStereo is unimplemented.") }, stereo = true) {
            warnings += it
        }
        assertFalse(reached)
        assertEquals("the user is told the call records mono", 1, warnings.size)
    }

    @Test
    fun `nothing is said when mono was wanted anyway`() {
        val warnings = mutableListOf<String>()
        DaemonStereo.request({ throw RemoteException("gone") }, stereo = false) { warnings += it }
        assertTrue(warnings.isEmpty())
    }
}
