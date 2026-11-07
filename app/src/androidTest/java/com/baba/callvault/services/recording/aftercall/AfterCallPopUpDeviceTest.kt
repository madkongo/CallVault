/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.util.Log
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baba.callvault.data.AppPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The keep-or-delete pop-up on a real Android build, with the app in the background as it is at call end: it
 * must draw (an overlay window, since Android 15 blocks background activity starts), step aside while a call's
 * audio mode is on, come back after, and clear on Keep. Written for the Android 15+ check of 2026-11-07, run on
 * an emulator; no call is needed because [AfterCallDecision.ask] is what a call end calls.
 *
 * Run on an emulator only, isolated: `./gradlew connectedDebugAndroidTest -PisolateTestApp
 * -Pandroid.testInstrumentationRunnerArguments.class=<this class>` with ANDROID_SERIAL set. It needs a real
 * (simulated) call, which only the host can make: when logcat shows "$TAG: shown", run
 * `adb -s emulator-5554 emu gsm call 5551234` (and `emu gsm accept`), then `emu gsm cancel` after "stepped
 * aside". An app cannot fake the call itself: setMode() needs MODIFY_AUDIO_SETTINGS, refused on Android 16.
 */
@RunWith(AndroidJUnit4::class)
class AfterCallPopUpDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val prefs = AppPreferences(context)
    private val name = "20261107_120000.000+0200_out_Device Test.ogg"

    @Before
    fun setUp() {
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        prefs.setAfterCallMode(AppPreferences.AfterCallMode.ASK)
        prefs.setAfterCallAskStyle(AppPreferences.AfterCallAskStyle.POPUP)
        device.pressHome()
        val file = File(context.cacheDir, "device-test.ogg").apply { writeBytes(ByteArray(64)) }
        AfterCallDecisions.hold(context, name, Uri.fromFile(file), "audio/ogg", isVoip = false)
    }

    @After
    fun tearDown() {
        runCatching { AfterCallDecision.keep(context, name) }
    }

    @Test
    fun popUpShowsInTheBackgroundStepsAsideDuringACallAndClearsOnKeep() {
        assertTrue("overlay permission", Settings.canDrawOverlays(context))

        AfterCallDecision.ask(context, name)
        assertNotNull("the pop-up draws over the home screen", device.wait(Until.findObject(By.text(QUESTION)), WAIT_MS))

        Log.i(TAG, "shown; start a call from the host")
        assertTrue("the pop-up steps aside during a call", device.wait(Until.gone(By.text(QUESTION)), CALL_WAIT_MS))

        Log.i(TAG, "stepped aside; end the call from the host")
        val back = device.wait(Until.findObject(By.text(QUESTION)), CALL_WAIT_MS)
        assertNotNull("the pop-up comes back after the call", back)

        Thread.sleep(TAP_GUARD_WAIT_MS)
        device.findObject(By.text("Keep")).click()
        assertTrue("Keep closes it", device.wait(Until.gone(By.text(QUESTION)), WAIT_MS))
        assertEquals("nothing left waiting", 0, AfterCallDecisions.all(context).size)
    }

    private companion object {
        const val QUESTION = "Keep this recording?"
        const val WAIT_MS = 5_000L
        const val TAP_GUARD_WAIT_MS = 600L
        const val CALL_WAIT_MS = 90_000L
        const val TAG = "CV:PopUpDeviceTest"
    }
}
