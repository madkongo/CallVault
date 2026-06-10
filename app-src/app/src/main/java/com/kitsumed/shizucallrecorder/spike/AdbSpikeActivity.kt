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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * AdbSpikeActivity — throwaway debug Activity for the embedded-ADB spike.
 *
 * Launch via adb:
 *   adb shell am start -n com.kfir.callvault/.spike.AdbSpikeActivity
 *
 * Flow (mirrors Shizuku): tap Start pairing → a foreground service ([AdbPairingService])
 * runs mDNS discovery and posts a notification with an inline reply field. You type
 * only the 6-digit code. On a successful pair the service AUTOMATICALLY runs
 * connect → `id` → forward test ([SpikeActions.runAutoChain]); results appear here and
 * in the result notification. "Run all" repeats that chain on demand (e.g. after a reboot).
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

@Composable
private fun AdbSpikeScreen(context: Context) {
    val log by SpikeLog.text.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

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
                "3. Enter the 6-digit code in the notification.\n" +
                "→ connect + id + forward run automatically after pairing.\n" +
                "Use 'Run all' to repeat the checks (e.g. after a reboot).",
            fontSize = 12.sp,
        )

        Button(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { SpikeLog.append("open dev options ERROR: ${it.message}") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open Developer options") }

        Button(
            onClick = { AdbPairingService.start(context); SpikeLog.append("→ Start pairing service") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start pairing") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { scope.launch { SpikeActions.runAutoChain(context) } },
                modifier = Modifier.weight(1f),
            ) { Text("Run all") }

            Button(
                onClick = { SpikeLog.clear() },
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
