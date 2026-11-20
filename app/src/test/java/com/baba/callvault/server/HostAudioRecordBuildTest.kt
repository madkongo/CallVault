/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Review N21: a vivo V2546A (China firmware, Android 17) lost 23 app calls. vivo's AudioRecord constructor crashed
 * without a Context, and the constructor-free rebuild then failed on Android 17 (`No field mChannelCount`). The
 * order of attempts is: the normal constructor (every other phone: unchanged), the rebuild (vivo on Android 16 and
 * earlier: unchanged), and only when both failed, a shell Application and the normal constructor once more.
 */
class HostAudioRecordBuildTest {

    private fun vivoCrash() = InvocationTargetException(
        NullPointerException("Attempt to invoke virtual method 'java.lang.String android.content.Context.getOpPackageName()' on a null object reference")
            .apply {
                stackTrace = arrayOf(
                    StackTraceElement("android.media.VivoAudioRecordImpl", "isSupportSubMixRecording", "X.java", 1),
                    StackTraceElement("android.media.AudioRecord", "<init>", "X.java", 1),
                    StackTraceElement("android.media.audiopolicy.AudioPolicy", "createAudioRecordSink", "X.java", 1),
                )
            },
    )

    private class Calls {
        var builds = 0
        var bypasses = 0
        var repairs = 0
    }

    @Test
    fun a_normal_build_never_tries_anything_else() {
        val c = Calls()
        val r = HostAudioRecord.buildWithRecovery(
            "test",
            build = { c.builds++; "normal" },
            bypass = { c.bypasses++; "bypass" },
            repair = { c.repairs++; true },
        )
        assertEquals("normal", r)
        assertEquals(listOf(1, 0, 0), listOf(c.builds, c.bypasses, c.repairs))
    }

    @Test
    fun any_other_failure_returns_null_without_bypass_or_repair() {
        val c = Calls()
        val r = HostAudioRecord.buildWithRecovery<String>(
            "test",
            build = { c.builds++; throw SecurityException("EX_SECURITY") },
            bypass = { c.bypasses++; "bypass" },
            repair = { c.repairs++; true },
        )
        assertNull(r)
        assertEquals(listOf(1, 0, 0), listOf(c.builds, c.bypasses, c.repairs))
    }

    @Test
    fun the_vivo_crash_uses_the_bypass_first_as_today_and_never_repairs_when_it_works() {
        val c = Calls()
        val r = HostAudioRecord.buildWithRecovery(
            "test",
            build = { c.builds++; throw vivoCrash() },
            bypass = { c.bypasses++; "bypass" },
            repair = { c.repairs++; true },
        )
        assertEquals("bypass", r)
        assertEquals(listOf(1, 1, 0), listOf(c.builds, c.bypasses, c.repairs))
    }

    @Test
    fun when_the_bypass_fails_too_a_shell_application_is_installed_and_the_normal_build_retried_once() {
        val c = Calls()
        val r = HostAudioRecord.buildWithRecovery(
            "test",
            build = { c.builds++; if (c.builds == 1) throw vivoCrash() else "normal after repair" },
            bypass = { c.bypasses++; throw NoSuchFieldException("No field mChannelCount in class Landroid/media/AudioRecord;") },
            repair = { c.repairs++; true },
        )
        assertEquals("normal after repair", r)
        assertEquals(listOf(2, 1, 1), listOf(c.builds, c.bypasses, c.repairs))
    }

    @Test
    fun a_repair_that_cannot_install_ends_with_null_and_no_second_build() {
        val c = Calls()
        val r = HostAudioRecord.buildWithRecovery<String>(
            "test",
            build = { c.builds++; throw vivoCrash() },
            bypass = { c.bypasses++; throw NoSuchFieldException("mChannelCount") },
            repair = { c.repairs++; false },
        )
        assertNull(r)
        assertEquals(listOf(1, 1, 1), listOf(c.builds, c.bypasses, c.repairs))
    }

    @Test
    fun a_retry_that_still_crashes_ends_with_null_after_exactly_one_retry() {
        val c = Calls()
        val r = HostAudioRecord.buildWithRecovery<String>(
            "test",
            build = { c.builds++; throw vivoCrash() },
            bypass = { c.bypasses++; throw NoSuchFieldException("mChannelCount") },
            repair = { c.repairs++; true },
        )
        assertNull(r)
        assertEquals(listOf(2, 1, 1), listOf(c.builds, c.bypasses, c.repairs))
    }
}
