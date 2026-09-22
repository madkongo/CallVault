/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the binder wire between the app and the daemon.
 *
 * `IRecorderService.aidl` declares no explicit transaction IDs, so every method's ID is its **position**
 * in the file. Adding a method anywhere but the end, or reordering two, silently renumbers the rest —
 * and a daemon left running by the previous version would then answer the wrong call for every
 * transaction after the edit. Nothing about that failure looks like a numbering problem from the app.
 *
 * So the numbering is asserted here. A failure means "you moved a method", and the fix is to put it
 * back and append instead.
 */
class RecorderTransactionCodesTest {

    private val first = 1 // android.os.IBinder.FIRST_CALL_TRANSACTION

    @Test
    fun the_aidl_method_order_has_not_changed() {
        assertEquals(first + 0, IRecorderService.Stub.TRANSACTION_startRecording)
        assertEquals(first + 1, IRecorderService.Stub.TRANSACTION_stopRecording)
        assertEquals(first + 2, IRecorderService.Stub.TRANSACTION_isRecording)
        assertEquals(first + 3, IRecorderService.Stub.TRANSACTION_destroy)
        assertEquals(first + 4, IRecorderService.Stub.TRANSACTION_startHandoff)
        assertEquals(first + 5, IRecorderService.Stub.TRANSACTION_armVoipCapture)
        assertEquals(first + 6, IRecorderService.Stub.TRANSACTION_disarmVoipCapture)
        assertEquals(first + 7, IRecorderService.Stub.TRANSACTION_startVoipRecording)
        assertEquals(first + 8, IRecorderService.Stub.TRANSACTION_voipFarPartyHeard)
        assertEquals(first + 9, IRecorderService.Stub.TRANSACTION_voipCallAppUid)
        assertEquals(first + 10, IRecorderService.Stub.TRANSACTION_voipCallerName)
        assertEquals(first + 11, IRecorderService.Stub.TRANSACTION_startHandoffHeld)
        assertEquals(first + 12, IRecorderService.Stub.TRANSACTION_stopHandoff)
        assertEquals(first + 13, IRecorderService.Stub.TRANSACTION_speakerTurns)
        // Appended for the Shizuku work. New methods go on the END, always: these ids are positional.
        assertEquals(first + 14, IRecorderService.Stub.TRANSACTION_grantAppOp)
        assertEquals(first + 15, IRecorderService.Stub.TRANSACTION_grantRole)
        assertEquals(first + 16, IRecorderService.Stub.TRANSACTION_hostUid)
        assertEquals(first + 17, IRecorderService.Stub.TRANSACTION_killStaleRecorders)
        assertEquals(first + 18, IRecorderService.Stub.TRANSACTION_setDiagnosticsEnabled)
        assertEquals(first + 19, IRecorderService.Stub.TRANSACTION_drainDiagnostics)
        assertEquals(first + 20, IRecorderService.Stub.TRANSACTION_setVoipPaused)
        assertEquals(first + 21, IRecorderService.Stub.TRANSACTION_setVoipSuspended)
        assertEquals(first + 22, IRecorderService.Stub.TRANSACTION_diagnosticDump)
        // Appended for issue #28b's overrun counter.
        assertEquals(first + 23, IRecorderService.Stub.TRANSACTION_captureDiagnostics)
        assertEquals(first + 24, IRecorderService.Stub.TRANSACTION_hostApkPath)
        assertEquals(first + 25, IRecorderService.Stub.TRANSACTION_voipCallAnswered)
    }

    @Test
    fun shizukus_destroy_code_is_the_one_shizuku_actually_sends() {
        // Shizuku's server destroys a user service by transacting this code directly on the binder —
        // it is not an AIDL method on our interface, so nothing else in the build would catch a typo.
        // Verified against RikkaApps/Shizuku-API's demo IUserService.aidl on 2026-08-24.
        assertEquals(16777114, RecorderServiceImpl.SHIZUKU_DESTROY_TRANSACTION)
    }

    @Test
    fun shizukus_destroy_code_cannot_collide_with_one_of_ours() {
        // The reason we can route it in onTransact instead of renumbering the AIDL: it sits far above
        // anything positional numbering will ever reach.
        val ours = first + 17
        assertTrue(
            "Shizuku's destroy code must stay clear of our AIDL range",
            RecorderServiceImpl.SHIZUKU_DESTROY_TRANSACTION > ours + 1000
        )
    }
}
