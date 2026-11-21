/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C140: a phone with no browser must not crash the app when a support or community link is tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenUrlTest {

    private class NoBrowserContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) = throw ActivityNotFoundException("no handler for ${intent.data}")
    }

    private class BrowserContext(base: Context) : ContextWrapper(base) {
        var started: Intent? = null
        override fun startActivity(intent: Intent) { started = intent }
    }

    private val base: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `no browser returns false instead of throwing and copies the link`() {
        val opened = NoBrowserContext(base).openUrl("https://example.org/support")

        assertFalse(opened)
        val clip = base.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals("https://example.org/support", clip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `a browser opens the link and reports success`() {
        val context = BrowserContext(base)

        assertTrue(context.openUrl("https://example.org/support"))
        assertEquals("https://example.org/support", context.started?.data.toString())
        assertEquals(Intent.ACTION_VIEW, context.started?.action)
    }
}
