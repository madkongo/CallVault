/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.annotation.SuppressLint
import android.media.AudioRecord
import com.baba.callvault.utils.AppLogger

/**
 * The one way the recorder host opens an [AudioRecord] from a capture source.
 *
 * vivo's ROM modified `AudioRecord`'s public constructor so that it crashes in a process with no Context — every
 * recorder-host process. 2.4.3 fixed only the app-call far-party sink; the report of 2026-09-28 15:30 (iQOO
 * V2507A, 2.4.3) showed the other four constructors failing the same way — the app-call microphone (so app calls
 * still failed), the direct capture and Resilient recording (so phone calls survived only on the scrcpy fallback).
 * Each had swallowed the crash with `runCatching { … }.getOrNull()`, so the log never said why.
 *
 * Tries the public constructor first — unchanged on every other phone — and only on the vendor crash
 * ([VendorConstructorCrash]) recovers; see [buildWithRecovery] for the order. Any other failure is logged with its
 * cause and returns null.
 */
internal object HostAudioRecord {

    private const val TAG = "CV:HostAudioRecord"

    /**
     * @param what a few words for the log ("VoIP mic", "direct voice-call"…).
     * @return the record — the caller still checks [AudioRecord.getState] — or null when it could not be built.
     */
    @SuppressLint("MissingPermission")
    fun open(what: String, source: Int, sampleRate: Int, channelMask: Int, encoding: Int, bufferBytes: Int): AudioRecord? =
        buildWithRecovery(
            what,
            build = { AudioRecord(source, sampleRate, channelMask, encoding, bufferBytes) },
            bypass = {
                BypassedAudioRecord.create(
                    capturePreset = source,
                    tags = emptyList(),
                    sampleRate = sampleRate,
                    channelMask = channelMask,
                    channelCount = Integer.bitCount(channelMask),
                    encoding = encoding,
                    bufferSizeInBytes = bufferBytes,
                )
            },
            repair = ShellApplication::install,
            onOtherFailure = { error ->
                AppLogger.w(TAG, "$what: AudioRecord(source=$source) threw: ${error.javaClass.simpleName}: ${error.message}")
            },
        )

    /**
     * Builds a record, recovering only from the crash a vendor put into `AudioRecord`'s constructor
     * ([VendorConstructorCrash]). In order, each step only after the one before it failed:
     *
     * 1. [build] — the public constructor. Every phone without that crash stops here, as before.
     * 2. [bypass] — the same record built without that constructor ([BypassedAudioRecord]); what vivo on Android 16
     *    and earlier has used since 2.4.3, kept first so those phones also behave as before.
     * 3. [repair] then [build] once more — review N21: on a vivo V2546A (Android 17) the bypass failed too (the
     *    private fields it sets were renamed), so the process is given a shell Application ([ShellApplication]) for
     *    the constructor's Context lookup, and the public constructor is tried again.
     *
     * Every failure is logged with its reason. Any other failure of [build] is logged and returns null, untouched.
     */
    internal fun <T : Any> buildWithRecovery(
        what: String,
        build: () -> T?,
        bypass: () -> T,
        repair: () -> Boolean,
        onOtherFailure: (Throwable) -> Unit = { error ->
            AppLogger.w(TAG, "$what: AudioRecord constructor threw: ${error.javaClass.simpleName}: ${error.message}")
        },
    ): T? {
        val normal = runCatching(build)
        if (normal.isSuccess) return normal.getOrNull()
        val error = normal.exceptionOrNull()!!
        if (!VendorConstructorCrash.matches(error)) {
            onOtherFailure(error)
            return null
        }
        AppLogger.w(TAG, "$what: the ROM's AudioRecord constructor crashed without a Context " +
            "(${VendorConstructorCrash.rootCause(error).message}); building it without that constructor")
        val bypassed = runCatching(bypass)
        bypassed.getOrNull()?.let {
            AppLogger.i(TAG, "$what: built without the vendor constructor")
            return it
        }
        val bypassError = bypassed.exceptionOrNull()!!
        AppLogger.e(TAG, "$what: building without the vendor constructor failed too: " +
            "${bypassError.javaClass.simpleName}: ${bypassError.message}", bypassError)
        ShellApplicationRepairGate.refusal(error, bypassError)?.let { why ->
            AppLogger.e(TAG, "$what: not giving the recorder a shell Application: $why; the record cannot be built")
            return null
        }
        AppLogger.w(TAG, "$what: the crash was a Context lookup and the rebuild does not fit this framework; " +
            "giving the recorder a shell Application and trying the constructor once more")
        if (!repair()) {
            AppLogger.e(TAG, "$what: no shell Application could be installed (reason logged above); the record cannot be built")
            return null
        }
        val retried = runCatching(build)
        retried.exceptionOrNull()?.let {
            AppLogger.e(TAG, "$what: the constructor failed again with a shell Application: " +
                "${VendorConstructorCrash.rootCause(it).javaClass.simpleName}: ${VendorConstructorCrash.rootCause(it).message}", it)
            return null
        }
        AppLogger.i(TAG, "$what: built by the public constructor with a shell Application")
        return retried.getOrNull()
    }

    /**
     * Starts [record]. Only on the crash Honor put into `startRecording()` ([VendorStartCrash], issue #53) does it
     * give the process a shell Application ([ShellApplication]) and start the same record again — the crash
     * comes before the real start, so the record is untouched. Any other failure propagates as before.
     */
    fun start(what: String, record: AudioRecord) =
        startWithRepair(what, start = { record.startRecording() }, repair = ShellApplication::install)

    internal fun startWithRepair(what: String, start: () -> Unit, repair: () -> Boolean) {
        val error = runCatching(start).exceptionOrNull() ?: return
        if (!VendorStartCrash.matches(error)) throw error
        AppLogger.w(TAG, "$what: the ROM's startRecording() needs an Application " +
            "(${VendorConstructorCrash.rootCause(error).message}); giving the recorder a shell one")
        if (!repair()) throw error
        start()
        AppLogger.i(TAG, "$what: started after the shell Application was installed")
    }
}
