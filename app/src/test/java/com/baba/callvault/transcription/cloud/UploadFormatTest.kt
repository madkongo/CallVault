/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which recordings go up as they are, and in one request. */
class UploadFormatTest {

    // ── Upload format ───────────────────────────────────────────────────────────────────────

    @Test
    fun `recordings go up as they are when the services take their container`() {
        assertEquals(UploadFormat.Named("audio.ogg", "audio/ogg"), UploadFormat.forFileName("Alice_20261029.ogg"))
        assertEquals(UploadFormat.Named("audio.ogg", "audio/ogg"), UploadFormat.forFileName("note.OPUS"))
        assertEquals("audio/mp4", UploadFormat.forFileName("call.m4a")!!.contentType)
        assertNull(UploadFormat.forFileName("voice.amr"))
        assertNull(UploadFormat.forFileName("no-extension"))
        assertNull(UploadFormat.forFileName(null))
    }

    @Test
    fun `a call under the size cap goes in one request, with no seam`() {
        // 📐 9.4 min of Opus at the default 24 kbps is about 1.7 MB.
        assertTrue(UploadFormat.sendsWhole("call.ogg", 1_700_000L))
        assertFalse(UploadFormat.sendsWhole("call.ogg", UploadFormat.SINGLE_UPLOAD_MAX_BYTES + 1))
        assertFalse(UploadFormat.sendsWhole("call.amr", 1_000L))
        assertFalse(UploadFormat.sendsWhole("call.ogg", 0L))
        // 60 min (the length limit) at 24 kbps is ~10.8 MB, under the cap.
        assertTrue(UploadFormat.sendsWhole("call.ogg", 60L * 60 * 24_000 / 8))
    }

    @Test
    fun `passes are remuxed only from codecs the muxer writes`() {
        assertEquals("audio/ogg", UploadFormat.forRemuxedCodec("audio/opus")!!.contentType)
        assertEquals("audio/mp4", UploadFormat.forRemuxedCodec("audio/mp4a-latm")!!.contentType)
        assertNull(UploadFormat.forRemuxedCodec("audio/3gpp"))
    }
}
