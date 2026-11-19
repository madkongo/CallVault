package com.baba.callvault.ui.viewmodels

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/**
 * Home crashed on every open on the OP9 (rf2, 2026-11-19): the C24 Wi-Fi callback was registered from `init`
 * before the property it emitted into was initialised, and Android calls onAvailable straight away when Wi-Fi is up.
 * [WifiChanges] registers only when collected, so there is nothing that can still be uninitialised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiChangesTest {

    private val cm: ConnectivityManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
    private val wifi: Network = ShadowNetwork.newInstance(1)

    @Test
    fun registersNothingUntilCollected() {
        WifiChanges.of(cm)
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
    }

    @Test
    fun aCallbackFiredRightAwayIsDeliveredNotACrash() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<Unit>()
        val job = launch { WifiChanges.of(cm).collect { seen += it } }
        val cb = shadowOf(cm).networkCallbacks.single()
        cb.onAvailable(wifi)
        cb.onLost(wifi)
        assertEquals(2, seen.size)
        job.cancel()
    }

    @Test
    fun stopsWatchingWhenTheCollectorGoesAway() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { WifiChanges.of(cm).collect { } }
        assertEquals(1, shadowOf(cm).networkCallbacks.size)
        job.cancel()
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
    }

    @Test
    fun noConnectivityServiceEndsQuietlyInsteadOfFailingHome() = runTest(UnconfinedTestDispatcher()) {
        assertTrue(WifiChanges.of(null).toList().isEmpty())
    }
}
