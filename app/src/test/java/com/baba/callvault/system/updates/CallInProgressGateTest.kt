/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Installing an APK over the running app kills the process, and any recording in flight dies with it.
 * Observed 2026-07-30: one call became two files, the second mislabelled, because on restart the app
 * read the still-running call as a fresh outgoing one.
 */
class CallInProgressGateTest {

    @Test
    fun `allows an install when nothing is happening`() {
        assertTrue(
            CallInProgressGate.mayInstall(
                telephonyState = TelephonyManager.CALL_STATE_IDLE,
                voipRecording = false,
            )
        )
    }

    @Test
    fun `blocks an install during an active carrier call`() {
        assertFalse(
            CallInProgressGate.mayInstall(
                telephonyState = TelephonyManager.CALL_STATE_OFFHOOK,
                voipRecording = false,
            )
        )
    }

    @Test
    fun `blocks an install while the phone is ringing`() {
        // A call about to be answered is a call about to be recorded; installing now loses its start.
        assertFalse(
            CallInProgressGate.mayInstall(
                telephonyState = TelephonyManager.CALL_STATE_RINGING,
                voipRecording = false,
            )
        )
    }

    @Test
    fun `blocks an install during a VoIP recording even though telephony reads idle`() {
        // THE CASE A TELEPHONY-ONLY CHECK MISSES: VoIP calls never set the telephony call state, so
        // checking only TelephonyManager would install straight through a WhatsApp recording.
        assertFalse(
            CallInProgressGate.mayInstall(
                telephonyState = TelephonyManager.CALL_STATE_IDLE,
                voipRecording = true,
            )
        )
    }

    @Test
    fun `blocks when both are active`() {
        assertFalse(
            CallInProgressGate.mayInstall(
                telephonyState = TelephonyManager.CALL_STATE_OFFHOOK,
                voipRecording = true,
            )
        )
    }

    @Test
    fun `blocks an install during an app call that is not being recorded`() {
        // Review C3: telephony idle and no VoIP recording, but the phone is in a call audio mode (an app call
        // recorded by nothing yet, or a OnePlus WhatsApp call that never reaches the telephony state, Q28).
        for (mode in listOf(2 /* IN_CALL */, 3 /* IN_COMMUNICATION */, 1 /* RINGTONE */)) {
            assertFalse(
                "mode $mode",
                CallInProgressGate.mayInstall(
                    telephonyState = TelephonyManager.CALL_STATE_IDLE,
                    voipRecording = false,
                    audioMode = mode,
                )
            )
        }
        assertTrue(CallInProgressGate.mayInstall(TelephonyManager.CALL_STATE_IDLE, voipRecording = false, audioMode = 0))
    }

    @Test
    fun `Shizuku mode is deferred during a call, not sent to the silent system installer`() {
        // Review C3: Shizuku mode used to answer UNAVAILABLE before the call check, and the caller then committed a
        // PackageInstaller session that installs with no prompt on Android 12+, mid-call.
        assertEquals(
            UpdateInstaller.ShellResult.DEFERRED_CALL_IN_PROGRESS,
            UpdateInstaller.preInstallVerdict(callFree = false, shizukuMode = true),
        )
        assertEquals(
            UpdateInstaller.ShellResult.UNAVAILABLE,
            UpdateInstaller.preInstallVerdict(callFree = true, shizukuMode = true),
        )
        assertEquals(
            UpdateInstaller.ShellResult.DEFERRED_CALL_IN_PROGRESS,
            UpdateInstaller.preInstallVerdict(callFree = false, shizukuMode = false),
        )
        assertNull(UpdateInstaller.preInstallVerdict(callFree = true, shizukuMode = false))
    }

    @Test
    fun `an unknown telephony state does not block`() {
        // Fail OPEN on a value we do not recognise: refusing every update because a device reports
        // something unexpected would be worse than the rare split recording this guards against.
        assertTrue(CallInProgressGate.mayInstall(telephonyState = 99, voipRecording = false))
    }
}
