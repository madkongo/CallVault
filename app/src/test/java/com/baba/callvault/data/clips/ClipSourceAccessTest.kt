package com.baba.callvault.data.clips

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C167: a failure inside the cut on an ordinary file must not become a second, whole-file copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipSourceAccessTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun realFile(): File = tmp.newFile("rec.ogg").apply { writeBytes(ByteArray(64) { it.toByte() }) }

    @Test
    fun `a block failing on a real file throws once and never makes the cache copy`() {
        val source = realFile()
        val copy = File(tmp.root, ".source.tmp")
        var blockRuns = 0
        var copies = 0
        try {
            ClipCutter.withSeekableSource(
                openDescriptor = { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) },
                copyTo = { copies++ },
                copyFile = copy,
                uriForLog = "content://x",
            ) { blockRuns++; throw IllegalStateException("cut failed") }
            fail("expected the cut's failure")
        } catch (e: IllegalStateException) {
            assertEquals("cut failed", e.message)
        }
        assertEquals(1, blockRuns)
        assertEquals(0, copies)
        assertFalse(copy.exists())
    }

    @Test
    fun `a descriptor that cannot be opened takes the copy path`() {
        val source = realFile()
        val copy = File(tmp.root, ".source.tmp")
        val result = ClipCutter.withSeekableSource(
            openDescriptor = { throw IOException("no provider") },
            copyTo = { it.writeBytes(source.readBytes()) },
            copyFile = copy,
            uriForLog = "content://x",
        ) { 7 }
        assertEquals(7, result)
        assertFalse(copy.exists())
    }

    @Test
    fun `a descriptor with no size, a pipe, takes the copy path`() {
        val copy = File(tmp.root, ".source.tmp")
        val pipe = ParcelFileDescriptor.createPipe()[0]
        var copied = false
        val result = ClipCutter.withSeekableSource(
            openDescriptor = { pipe },
            copyTo = { copied = true; it.writeBytes(ByteArray(4)) },
            copyFile = copy,
            uriForLog = "content://x",
        ) { "ok" }
        assertEquals("ok", result)
        assertTrue(copied)
    }

    @Test
    fun `a null result from the block on a real file does not trigger a copy`() {
        val source = realFile()
        var copies = 0
        val result: String? = ClipCutter.withSeekableSource(
            openDescriptor = { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) },
            copyTo = { copies++ },
            copyFile = File(tmp.root, ".source.tmp"),
            uriForLog = "content://x",
        ) { null }
        assertEquals(null, result)
        assertEquals(0, copies)
    }

    @Test
    fun `a source the extractor cannot set up on falls back to the copy`() {
        val source = realFile()
        val copy = File(tmp.root, ".source.tmp")
        var runs = 0
        val result = ClipCutter.withSeekableSource(
            openDescriptor = { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) },
            copyTo = { it.writeBytes(source.readBytes()) },
            copyFile = copy,
            uriForLog = "content://x",
        ) { if (runs++ == 0) throw ClipCutter.SourceUnreadable(IllegalStateException("no pread")) else "copied" }
        assertEquals("copied", result)
        assertEquals(2, runs)
    }

    @Test
    fun `an io failure reading the real file also falls back to the copy`() {
        val source = realFile()
        var runs = 0
        val result = ClipCutter.withSeekableSource(
            openDescriptor = { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) },
            copyTo = { it.writeBytes(source.readBytes()) },
            copyFile = File(tmp.root, ".source.tmp"),
            uriForLog = "content://x",
        ) { if (runs++ == 0) throw IOException("read failed") else 1 }
        assertEquals(1, result)
    }
}
