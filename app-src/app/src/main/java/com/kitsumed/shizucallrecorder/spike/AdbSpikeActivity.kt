/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AdbSpikeActivity — throwaway debug Activity for the embedded-ADB spike.
 *
 * Launch via adb:
 *   adb shell am start -n com.kfir.callvault/.spike.AdbSpikeActivity
 *
 * UX (mirrors Shizuku): pairing is driven by [AdbPairingService], a foreground
 * service that runs mDNS discovery and posts a notification with an inline reply
 * field — the user types only the 6-digit code, in the notification. The pairing
 * port and the connect port are both discovered over mDNS ([AdbMdns]); the user
 * never reads or types a port.
 *
 * Buttons:
 *   Start pairing → starts [AdbPairingService]
 *   Connect       → discover _adb-tls-connect._tcp, then SpikeAdbManager.connect()
 *   Run id        → openStream("shell:id")  (expect uid=2000(shell))
 *   Forward test  → openStream("shell:cat /proc/uptime")
 *
 * Will be removed at the end of the spike — do NOT ship to production.
 */
class AdbSpikeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AdbSpikeScreen(applicationContext)
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }
}

// ---- ADB operations (called on IO dispatcher by the screen) ----

private const val MDNS_TIMEOUT_MS = 25_000L

/**
 * Discovers the _adb-tls-connect._tcp port over mDNS, then connects with the
 * persisted key. Verified signature: `connect(String host, int port): boolean`.
 */
private fun doConnect(context: Context): String {
    val port = AdbMdns.discoverPort(context, AdbMdns.TLS_CONNECT, MDNS_TIMEOUT_MS)
        ?: return "connect: no _adb-tls-connect._tcp found — is Wireless debugging ON and paired?"
    val ok = SpikeAdbManager.getInstance(context).connect("127.0.0.1", port)
    return "connect(127.0.0.1:$port) → $ok"
}

/**
 * Opens a one-shot ADB shell stream, reads its entire output to a String, and closes it.
 *   AbsAdbConnectionManager.openStream(String): AdbStream ; AdbStream.openInputStream(): AdbInputStream
 */
private fun doShellCommand(context: Context, command: String): String {
    val stream = SpikeAdbManager.getInstance(context).openStream(command)
    return stream.use { s ->
        s.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

// ---- Compose UI ----

@Composable
private fun AdbSpikeScreen(context: Context) {
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
            text = "1. Tap Start pairing.\n" +
                "2. Open Settings → Developer options → Wireless debugging → " +
                "Pair device with pairing code.\n" +
                "3. Enter the 6-digit code in the notification that pops up.\n" +
                "4. Then tap Connect, then Run id (expect uid=2000).",
            fontSize = 12.sp,
        )

        Button(
            onClick = { AdbPairingService.start(context); appendLog("→ Start pairing service") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start pairing") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { launchIo("Connect (mDNS)") { doConnect(context) } },
                modifier = Modifier.weight(1f),
            ) { Text("Connect") }

            Button(
                onClick = { launchIo("Run id") { doShellCommand(context, "shell:id") } },
                modifier = Modifier.weight(1f),
            ) { Text("Run id") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { launchIo("Forward test") { doShellCommand(context, "shell:cat /proc/uptime") } },
                modifier = Modifier.weight(1f),
            ) { Text("Forward test") }

            Button(
                onClick = { log = "Log cleared.\n" },
                modifier = Modifier.weight(1f),
            ) { Text("Clear log") }
        }

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
