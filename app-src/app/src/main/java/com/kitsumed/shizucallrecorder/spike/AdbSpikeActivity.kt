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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AdbSpikeActivity — throwaway debug Activity that exercises [SpikeAdbManager].
 *
 * Launch via adb:
 *   adb shell am start -n com.kfir.callvault/.spike.AdbSpikeActivity
 *
 * All ADB calls run on [Dispatchers.IO]; results are appended to an in-memory log
 * displayed as a scrolling Text on screen. The UI never crashes on failure — every
 * call is wrapped in [runCatching].
 *
 * API signatures used (verified via `javap` on libadb-android 3.1.1):
 *   pair      : AbsAdbConnectionManager.pair(String, int, String): boolean
 *   connect   : AbsAdbConnectionManager.connect(String, int): boolean
 *   openStream: AbsAdbConnectionManager.openStream(String): AdbStream  throws IOException, InterruptedException
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

/**
 * Calls [SpikeAdbManager.pair] with the given host/port/code.
 * Verified signature: `pair(String host, int port, String pairingCode): boolean`
 */
private fun doPair(context: Context, host: String, pairingPort: Int, pairingCode: String): String {
    val ok = SpikeAdbManager.getInstance(context).pair(host, pairingPort, pairingCode)
    return "pair($host, $pairingPort, ***) → $ok"
}

/**
 * Calls [SpikeAdbManager.connect].
 * Verified signature: `connect(String host, int port): boolean`
 */
private fun doConnect(context: Context, host: String, connectPort: Int): String {
    val ok = SpikeAdbManager.getInstance(context).connect(host, connectPort)
    return "connect($host, $connectPort) → $ok"
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
    var host by remember { mutableStateOf("127.0.0.1") }
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("5555") }
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

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = pairingPort,
            onValueChange = { pairingPort = it },
            label = { Text("Pairing port") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("Pairing code") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = connectPort,
            onValueChange = { connectPort = it },
            label = { Text("Connect port") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val port = pairingPort.toIntOrNull()
                        ?: return@Button appendLog("ERROR: pairing port must be an integer")
                    launchIo("Pair $host:$port") { doPair(context, host, port, pairingCode) }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Pair") }

            Button(
                onClick = {
                    val port = connectPort.toIntOrNull()
                        ?: return@Button appendLog("ERROR: connect port must be an integer")
                    launchIo("Connect $host:$port") { doConnect(context, host, port) }
                },
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
