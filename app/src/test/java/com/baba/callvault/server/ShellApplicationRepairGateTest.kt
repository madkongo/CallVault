/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Review N21 rework: the process-global shell Application is installed only when it can be the fix — the vendor
 * constructor crashed looking up a Context, AND the constructor-free rebuild failed because this framework is shaped
 * differently (a field, method or class missing). A rebuild that failed at run time (native_setup returned an error,
 * the mic is busy) must not change a phone's identity: vivo on Android 16 and earlier works with the rebuild today.
 */
class ShellApplicationRepairGateTest {

    private fun npe(message: String?, vararg frames: Pair<String, String>) = InvocationTargetException(
        NullPointerException(message).apply {
            stackTrace = frames.map { (c, m) -> StackTraceElement(c, m, "X.java", 1) }.toTypedArray()
        },
    )

    private val vivoFrames = arrayOf(
        "android.media.VivoAudioRecordImpl" to "isSupportSubMixRecording",
        "android.media.AudioRecord" to "<init>",
        "android.media.AudioRecord" to "<init>",
        "android.media.audiopolicy.AudioPolicy" to "createAudioRecordSink",
    )

    /** The message exactly as the V2546A (Android 17) field log of 2026-11-19 15:51:23 has it. */
    private val fieldReportCrash = npe(
        "Attempt to invoke virtual method 'java.lang.String android.content.Context.getOpPackageName()' on a null object reference",
        *vivoFrames,
    )

    /** The bypass failure from the same log. */
    private val fieldReportBypass = NoSuchFieldException(
        "No field mChannelCount in class Landroid/media/AudioRecord; (declaration of 'android.media.AudioRecord' appears in /system/framework/framework.jar!classes3.dex)",
    )

    @Test
    fun the_field_report_is_repaired() {
        assertTrue(ShellApplicationRepairGate.pointsAtContextLookup(fieldReportCrash))
        assertTrue(ShellApplicationRepairGate.failedStructurally(fieldReportBypass))
        assertNull(ShellApplicationRepairGate.refusal(fieldReportCrash, fieldReportBypass))
    }

    @Test
    fun other_context_lookups_on_null_count() {
        assertTrue(ShellApplicationRepairGate.pointsAtContextLookup(npe(
            "Attempt to invoke virtual method 'android.content.AttributionSource android.content.Context.getAttributionSource()' on a null object reference",
            *vivoFrames,
        )))
        assertTrue(ShellApplicationRepairGate.pointsAtContextLookup(npe(
            "Attempt to invoke virtual method 'android.content.pm.PackageManager android.content.Context.getPackageManager()' on a null object reference",
            *vivoFrames,
        )))
        assertTrue(ShellApplicationRepairGate.pointsAtContextLookup(npe(
            "Attempt to invoke virtual method 'java.lang.String android.app.Application.getOpPackageName()' on a null object reference",
            *vivoFrames,
        )))
    }

    @Test
    fun a_vendor_npe_about_something_else_is_not_repaired() {
        val other = npe(
            "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference",
            *vivoFrames,
        )
        assertFalse(ShellApplicationRepairGate.pointsAtContextLookup(other))
        assertNotNull(ShellApplicationRepairGate.refusal(other, fieldReportBypass))
        assertFalse(ShellApplicationRepairGate.pointsAtContextLookup(npe(null, *vivoFrames)))
    }

    @Test
    fun a_bypass_that_failed_at_run_time_is_not_repaired() {
        val native = InvocationTargetException(IllegalStateException("native_setup returned -38"))
        assertFalse(ShellApplicationRepairGate.failedStructurally(native))
        assertNotNull(ShellApplicationRepairGate.refusal(fieldReportCrash, native))
        assertNotNull(ShellApplicationRepairGate.refusal(fieldReportCrash, SecurityException("EX_SECURITY")))
    }

    @Test
    fun every_shape_mismatch_counts_as_structural_wherever_it_sits_in_the_chain() {
        assertTrue(ShellApplicationRepairGate.failedStructurally(NoSuchMethodException("native_setup")))
        assertTrue(ShellApplicationRepairGate.failedStructurally(ClassNotFoundException("android.media.AudioFormat\$ChannelMasks")))
        assertTrue(ShellApplicationRepairGate.failedStructurally(RuntimeException("wrapped", NoSuchFieldException("mChannelMask"))))
        assertTrue(ShellApplicationRepairGate.failedStructurally(NoSuchFieldError("mChannelMasks")))
    }
}
