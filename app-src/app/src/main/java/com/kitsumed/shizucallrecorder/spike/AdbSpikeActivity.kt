/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.spike

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AdbSpikeActivity — throwaway debug Activity that exercises [SpikeAdbManager].
 *
 * Launch via adb:
 *   adb shell am start -n com.kfir.callvault/.spike.AdbSpikeActivity
 *
 * UX goal (like Shizuku): the user only types the 6-digit pairing code. The pairing
 * port AND the connect port are discovered automatically over mDNS — the user never
 * reads or types a port. The phone's "Pair device with pairing code" dialog must be
 * open while pairing (that is what advertises the _adb-tls-pairing._tcp service).
 *
 * All ADB calls run on [Dispatchers.IO]; results are appended to an on-screen log.
 * Every call is wrapped in [runCatching] so the UI never crashes on failure.
 *
 * API signatures used (verified via `javap` on libadb-android 3.1.1):
 *   mDNS      : AdbMdns(Context, String serviceType, OnAdbDaemonDiscoveredListener{ onPortChanged(InetAddress, int) }); start()/stop()
 *               SERVICE_TYPE_TLS_PAIRING = "adb-tls-pairing", SERVICE_TYPE_TLS_CONNECT = "adb-tls-connect"
 *   pair      : AbsAdbConnectionManager.pair(String host, int port, String code): boolean
 *   connect   : AbsAdbConnectionManager.autoConnect(Context, long timeoutMs): boolean  (mDNS connect discovery)
 *   openStream: AbsAdbConnectionManager.openStream(String): AdbStream
 *   read      : AdbStream.openInputStream(): AdbInputStream  (extends java.io.InputStream)
 *
 * Will be removed at the end of the spike — do NOT ship to production.
 */
class AdbSpikeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AdbSpikeScreen(applicationContext)
                }
            }
        }
    }
}

// ---- ADB operations (called on IO dispatcher by the screen) ----

private const val MDNS_DISCOVERY_TIMEOUT_SECONDS = 25L

/**
 * Discovers an mDNS-advertised ADB service of [serviceType] and returns its (host, port),
 * or null if nothing was found within [MDNS_DISCOVERY_TIMEOUT_SECONDS].
 *
 * Bridges [AdbMdns]'s async `onPortChanged` callback to a blocking call via a latch,
 * mirroring how the library's own `autoConnect` waits for discovery.
 */
private fun discoverService(context: Context, serviceType: String): Pair<String, Int>? {
    val addrRef = AtomicReference<InetAddress?>(null)
    val portRef = AtomicInteger(-1)
    val latch = CountDownLatch(1)
    val mdns = AdbMdns(context, serviceType) { address, port ->
        if (address != null && port > 0) {
            addrRef.set(address)
            portRef.set(port)
            latch.countDown()
        }
    }
    mdns.start()
    try {
        val found = latch.await(MDNS_DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!found) return null
        val host = addrRef.get()?.hostAddress ?: return null
        return host to portRef.get()
    } finally {
        mdns.stop()
    }
}

/**
 * Pairs using ONLY the 6-digit code: the pairing host/port are auto-discovered over mDNS
 * (service `adb-tls-pairing`), which Android advertises while the "Pair device with
 * pairing code" dialog is open.
 */
private fun doPairAutoDiscover(context: Context, pairingCode: String): String {
    val (host, port) = discoverService(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING)
        ?: return "pair: no pairing service found via mDNS — is the phone's " +
            "'Pair device with pairing code' dialog open?"
    val ok = SpikeAdbManager.getInstance(context).pair(host, port, pairingCode)
    return "pair(mDNS $host:$port, ***) → $ok"
}

/**
 * Connects using mDNS connect discovery — no port needed.
 * Verified signature: `autoConnect(Context, long): boolean`.
 */
private fun doConnectAuto(context: Context): String {
    val ok = SpikeAdbManager.getInstance(context).autoConnect(context, 25_000L)
    return "autoConnect(mDNS) → $ok"
}

/**
 * Opens a one-shot ADB shell stream, reads its entire output to a String, and closes it.
 *
 * Verified API:
 *   AbsAdbConnectionManager.openStream(String destination): AdbStream
 *   AdbStream.openInputStream(): AdbInputStream  (extends java.io.InputStream)
 */
private fun doShellCommand(context: Context, command: String): String {
    val stream = SpikeAdbManager.getInstance(context).openStream(command)
    return stream.use { s ->
        s.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

// ---- Compose UI ----

/**
 * Stateful Compose screen for the ADB spike.
 *
 * All ADB actions are launched via [rememberCoroutineScope] on [Dispatchers.IO].
 * Each result (or exception message) is appended to a scrolling monospace log.
 */
@Composable
private fun AdbSpikeScreen(context: Context) {
    var pairingCode by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("Ready.\n") }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun appendLog(line: String) {
        log += "$line\n"
    }

    fun launchIo(label: String, action: () -> String) {
        scope.launch {
            appendLog("→ $label …")
            val result = withContext(Dispatchers.IO) {
                runCatching { action() }.fold(onSuccess = { it }, onFailure = { "ERROR: ${it.message}" })
            }
            appendLog(result)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "ADB Spike", style = MaterialTheme.typography.headlineSmall)

        Text(
            text = "Open the phone's 'Pair device with pairing code' dialog, type the " +
                "6-digit code below, then tap Pair. The port is found automatically.",
            fontSize = 12.sp,
        )

        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("Pairing code") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val code = pairingCode.trim()
                    if (code.isEmpty()) return@Button appendLog("ERROR: enter the pairing code first")
                    launchIo("Pair (auto-discover port)") { doPairAutoDiscover(context, code) }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Pair") }

            Button(
                onClick = { launchIo("Connect (auto)") { doConnectAuto(context) } },
                modifier = Modifier.weight(1f),
            ) { Text("Connect") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { launchIo("Run id") { doShellCommand(context, "shell:id") } },
                modifier = Modifier.weight(1f),
            ) { Text("Run id") }

            Button(
                onClick = { launchIo("Forward test") { doShellCommand(context, "shell:cat /proc/uptime") } },
                modifier = Modifier.weight(1f),
            ) { Text("Forward test") }
        }

        Button(
            onClick = { log = "Log cleared.\n" },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Clear log") }

        Text(
            text = log,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}
