/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import androidx.work.NetworkType
import com.baba.callvault.transcription.model.DownloadNetworkChoice.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** When tapping Download asks about mobile data, and what each answer queues. */
class DownloadNetworkChoiceTest {

    @Test
    fun `mobile data with internet asks first`() {
        assertEquals(Decision.ASK, DownloadNetworkChoice.decide(hasInternet = true, isMetered = true))
    }

    @Test
    fun `wifi queues as before without asking`() {
        assertEquals(Decision.QUEUE_UNMETERED, DownloadNetworkChoice.decide(hasInternet = true, isMetered = false))
    }

    @Test
    fun `no internet queues for wifi without asking`() {
        assertEquals(Decision.QUEUE_UNMETERED, DownloadNetworkChoice.decide(hasInternet = false, isMetered = true))
        assertEquals(Decision.QUEUE_UNMETERED, DownloadNetworkChoice.decide(hasInternet = false, isMetered = false))
    }

    @Test
    fun `waiting for wifi needs an unmetered network and mobile data any connection`() {
        assertEquals(NetworkType.UNMETERED, DownloadNetwork.WIFI_ONLY.networkType)
        assertEquals(NetworkType.CONNECTED, DownloadNetwork.ANY.networkType)
    }
}
