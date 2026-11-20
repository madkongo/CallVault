/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Whether a throwable is the crash a vendor put into `AudioRecord`'s public constructor — vivo's
 * `VivoAudioRecordImpl.isSupportSubMixRecording()` dereferencing a Context the recorder host does not have.
 * Recognised by shape, not by brand: a NullPointerException raised inside code the constructor CALLED — the
 * frame that threw is not `AudioRecord`, and a frame below it is `AudioRecord.<init>`.
 */
internal object VendorConstructorCrash {

    fun matches(error: Throwable): Boolean {
        val root = rootCause(error)
        if (root !is NullPointerException) return false
        val frames = root.stackTrace
        val ctor = frames.indexOfFirst { it.className == "android.media.AudioRecord" && it.methodName == "<init>" }
        return ctor > 0 && frames[0].className != "android.media.AudioRecord"
    }

    fun rootCause(error: Throwable): Throwable {
        var e = error
        while (e.cause != null && e.cause !== e) e = e.cause!!
        return e
    }
}

/**
 * Whether a throwable is the crash a vendor put into `AudioRecord.startRecording()` — Honor's MagicOS 11
 * (Android 17) audit block, which calls `Objects.requireNonNull(ActivityThread.currentApplication())` before
 * the real start, in a recorder host that has no Application (issue #53). Same shape rule as
 * [VendorConstructorCrash]: a NullPointerException raised inside code `startRecording` CALLED, not in
 * `AudioRecord` itself.
 */
internal object VendorStartCrash {

    fun matches(error: Throwable): Boolean {
        val root = VendorConstructorCrash.rootCause(error)
        if (root !is NullPointerException) return false
        val frames = root.stackTrace
        val start = frames.indexOfFirst { it.className == "android.media.AudioRecord" && it.methodName == "startRecording" }
        return start > 0 && frames[0].className != "android.media.AudioRecord"
    }
}

/**
 * Whether the shell Application may be installed after vivo's constructor crash (review N21). It is process-global
 * and one-way, so it runs only where it can be the fix:
 *
 * - the constructor crashed looking up a Context — the NPE names a `Context` (or `Application`) method called on
 *   null, as the V2546A log does (`…android.content.Context.getOpPackageName()' on a null object reference`); and
 * - the constructor-free rebuild failed because this framework is shaped differently — a field, method or class
 *   missing somewhere in its cause chain. A rebuild that failed at run time (`native_setup` returned an error, the
 *   mic is busy) says nothing about Context, and vivo on Android 16 and earlier must keep the identity it has.
 */
internal object ShellApplicationRepairGate {

    /** A Context or Application method invoked on null, as ART words it. */
    private val CONTEXT_ON_NULL =
        Regex("""method '[^']*\bandroid\.(content\.Context|content\.ContextWrapper|app\.Application)\.\w+\([^']*\)' on a null object reference""")

    /** @return null when the repair may run, otherwise the reason it may not (for the log). */
    fun refusal(constructorError: Throwable, bypassError: Throwable): String? = when {
        !pointsAtContextLookup(constructorError) ->
            "the constructor crash is not a Context lookup on null (${VendorConstructorCrash.rootCause(constructorError).message})"
        !failedStructurally(bypassError) ->
            "the rebuild failed at run time, not for a missing field, method or class " +
                "(${VendorConstructorCrash.rootCause(bypassError).javaClass.simpleName}: ${VendorConstructorCrash.rootCause(bypassError).message})"
        else -> null
    }

    fun pointsAtContextLookup(error: Throwable): Boolean {
        val root = VendorConstructorCrash.rootCause(error)
        return root is NullPointerException && root.message?.let { CONTEXT_ON_NULL.containsMatchIn(it) } == true
    }

    fun failedStructurally(error: Throwable): Boolean =
        generateSequence(error) { e -> e.cause?.takeIf { it !== e } }.any {
            it is NoSuchFieldException || it is NoSuchMethodException || it is ClassNotFoundException ||
                it is NoSuchFieldError || it is NoSuchMethodError || it is NoClassDefFoundError
        }
}
