/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.system.diagnostics.SystemLogCollector
import com.baba.callvault.system.shareLogFiles
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The one place the debug report and the system report are built, for Settings ▸ Debug and for the setup
 * screens' "Send a report" link, so both hand over exactly the same thing.
 */
object DebugReportBuilder {

    /** Hard ceiling on the optional system-log half of a debug report. */
    const val SYSTEM_REPORT_BUDGET_MS = 45_000L

    /**
     * Builds the app's debug report and, within [SYSTEM_REPORT_BUDGET_MS], the system report. The first file is
     * null only when there is nothing to report; the second when the system half could not be collected in time.
     *
     * The system half makes up to seven ADB round-trips and is BOUNDED on purpose: it runs on a scope of its own
     * and only the await is timed out, because the work is one long blocking block that cancellation cannot
     * interrupt. A hang there must never throw away the app report that is already built.
     */
    suspend fun build(context: Context): Pair<File?, File?> {
        val report = withContext(Dispatchers.IO) { AppLogger.buildShareableReport(context) }
        val collector = CoroutineScope(Dispatchers.IO)
        val pending = collector.async { SystemLogCollector.buildReport(context) }
        val systemReport = withTimeoutOrNull(SYSTEM_REPORT_BUDGET_MS) { pending.await() }
        if (systemReport == null) {
            collector.cancel()
            AppLogger.w(
                "CV:Settings",
                "System report not attached (empty or over ${SYSTEM_REPORT_BUDGET_MS}ms); " +
                    "going on with the app report alone"
            )
        }
        return report to systemReport
    }
}

/**
 * Wraps one setup screen and pins the "Having trouble? Send a report" link under it.
 *
 * The link lives OUTSIDE the screen's own scrolling content, in a bar of its own below it, so it is never
 * scrolled away, never pushed off by a long translation (the text wraps and the bar grows, the screen above
 * gives up the room) and never left behind the keyboard ([imePadding] on the whole column). Insets are applied
 * here and consumed, so the wrapped screen's own system-bar padding does not add a second gap.
 */
@Composable
fun SetupScreenWithReportLink(content: @Composable () -> Unit) {
    Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
        Box(modifier = Modifier.weight(1f)) { content() }
        SetupReportLink()
    }
}

/** The link itself and what tapping it does: build both reports off the main thread, then the share sheet. */
@Composable
private fun SetupReportLink() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    if (preparing) R.string.setup_report_preparing else R.string.setup_report_link
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .testTag("setup_report_link")
                    .fillMaxWidth()
                    // 48dp at least: a link under a big button is a small target, and this one is for a stuck user.
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !preparing, role = Role.Button) {
                        scope.launch {
                            preparing = true
                            try {
                                AppLogger.i("CV:SetupReport", "Send a report tapped on a setup screen")
                                val (report, systemReport) = DebugReportBuilder.build(context)
                                if (report != null) {
                                    context.shareLogFiles(listOfNotNull(report, systemReport))
                                } else {
                                    Toast.makeText(context, R.string.settings_bugreport_share_empty, Toast.LENGTH_LONG).show()
                                }
                            } finally {
                                preparing = false
                            }
                        }
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
