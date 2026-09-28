/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * The app-call sink falls back to building its AudioRecord without the public constructor only for the crash
 * a vendor put into that constructor — vivo's, from a real report (iQOO V2507A, OriginOS 6, Android 16,
 * 2026-09-28). Every other failure keeps its old behaviour: logged, no sink.
 */
class VoipAudioPolicyVendorCrashTest {

    private fun npe(vararg frames: Pair<String, String>) = NullPointerException(
        "Attempt to invoke virtual method 'java.lang.String android.content.Context.getOpPackageName()' on a null object reference"
    ).apply { stackTrace = frames.map { (c, m) -> StackTraceElement(c, m, "X.java", 1) }.toTypedArray() }

    @Test
    fun the_vivo_crash_as_reported_is_recognised() {
        val cause = npe(
            "android.media.VivoAudioRecordImpl" to "isSupportSubMixRecording",
            "android.media.AudioRecord" to "<init>",
            "android.media.AudioRecord" to "<init>",
            "android.media.audiopolicy.AudioPolicy" to "createAudioRecordSink",
        )
        assertTrue(VendorConstructorCrash.matches(InvocationTargetException(cause)))
    }

    @Test
    fun a_null_pointer_in_aosp_code_alone_is_not_a_vendor_crash() {
        val cause = npe("android.media.AudioRecord" to "<init>", "android.media.audiopolicy.AudioPolicy" to "createAudioRecordSink")
        assertFalse(VendorConstructorCrash.matches(InvocationTargetException(cause)))
    }

    @Test
    fun a_null_pointer_outside_the_constructor_is_not_a_vendor_crash() {
        val cause = npe("android.media.VivoAudioRecordImpl" to "somethingElse", "com.baba.callvault.server.VoipAudioPolicy" to "createSink")
        assertFalse(VendorConstructorCrash.matches(InvocationTargetException(cause)))
    }

    @Test
    fun other_errors_are_not_vendor_crashes() {
        assertFalse(VendorConstructorCrash.matches(InvocationTargetException(IllegalStateException("no policy"))))
        assertFalse(VendorConstructorCrash.matches(SecurityException("EX_SECURITY")))
    }
}
