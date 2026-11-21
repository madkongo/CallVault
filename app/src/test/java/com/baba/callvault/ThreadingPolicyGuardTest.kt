package com.baba.callvault

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ratchet for docs/dev-notes/threading-policy.md (F4). Hand-made coroutine scopes, runBlocking and raw threads
 * have no named owner yet (the main-thread defects C74, C85, C108, C132 came from that gap), so the policy does not
 * ban the ones that exist; it stops NEW ones from appearing unnoticed. A file may not use more of each than its
 * baseline below, and a file missing from the baseline may use none. When you remove one, lower the number; when a
 * new one is justified, name its owner in the policy and raise the number in the same change. GlobalScope is banned.
 */
class ThreadingPolicyGuardTest {

    private val scopePattern = Regex("""\bCoroutineScope\(""")
    private val blockPattern = Regex("""\brunBlocking\s*[{(]""")
    private val threadPattern = Regex("""(?<![\w.])Thread\s*[({]|\bHandlerThread\(|\bExecutors\.new\w+\(""")
    private val globalPattern = Regex("""\bGlobalScope\b""")

    private val scopeBaseline = mapOf(
        "CallVaultApplication.kt" to 1,
        "integrations/adb/AdbPairingService.kt" to 1,
        "services/boot/AdbConnectionService.kt" to 1,
        "services/call/CallSessionManager.kt" to 1,
        "services/recording/AudioRecordingEngine.kt" to 1,
        "services/recording/RecordingForegroundService.kt" to 5,
        "services/recording/VoipRecordingCoordinator.kt" to 7,
        "services/recording/VoipToggleEffects.kt" to 1,
        "system/diagnostics/battery/BatteryUseCollector.kt" to 1,
        "transcription/cloud/CloudKeySaver.kt" to 1,
        "ui/common/SetupReportLink.kt" to 1,
        "ui/screens/AfterCallOverlay.kt" to 1,
        "ui/viewmodels/SettingsReconcile.kt" to 1,
        "utils/AppLogger.kt" to 1
    )

    private val blockBaseline = mapOf(
        "CallVaultApplication.kt" to 1,
        "services/recording/AudioRecordingEngine.kt" to 1,
        "services/recording/VoipRecordingCoordinator.kt" to 1,
        "services/recording/aftercall/AfterCallDecision.kt" to 1
    )

    private val threadBaseline = mapOf(
        "CallVaultApplication.kt" to 11,
        "integrations/adb/AdbConnectionManager.kt" to 2,
        "integrations/adb/AdbShell.kt" to 6,
        "integrations/adb/ConnectPortScan.kt" to 1,
        "integrations/adb/ShizukuRestarter.kt" to 2,
        "integrations/scrcpy/ScrcpyLauncher.kt" to 2,
        "server/AppStartBinderSender.kt" to 1,
        "server/BoundedProcess.kt" to 1,
        "server/DirectAudioRecorderSession.kt" to 2,
        "server/RecorderServer.kt" to 1,
        "server/RecorderServerLauncher.kt" to 2,
        "server/RecorderServiceImpl.kt" to 1,
        "server/RecorderSession.kt" to 2,
        "server/ShizukuLifecycleWatcher.kt" to 2,
        "server/VoipCaptureSession.kt" to 3,
        "server/WifiJoinHost.kt" to 2,
        "server/WirelessDebuggingApproval.kt" to 1,
        "server/echo/EchoAligner.kt" to 1,
        "services/recording/AppPauseWatch.kt" to 1,
        "services/recording/DaemonKeepAliveService.kt" to 11,
        "services/recording/MicOpAutoHeal.kt" to 1,
        "services/recording/OrphanRecorderSession.kt" to 1,
        "services/recording/RecordingSwitch.kt" to 1,
        "services/recording/RecordingTileService.kt" to 1,
        "services/recording/UsbDebuggingActionReceiver.kt" to 1,
        "services/recording/VoipCallDetector.kt" to 1,
        "services/recording/VoipRecordingCoordinator.kt" to 4,
        "services/recording/VoipRingListenerAccess.kt" to 1,
        "services/recording/WifiJoinWatch.kt" to 1,
        "services/recording/WirelessDebuggingActionReceiver.kt" to 1,
        "services/recording/aftercall/AfterCallDecisionReceiver.kt" to 1,
        "services/recording/aftercall/AfterCallSettings.kt" to 1,
        "services/recording/handoff/HandoffReceiver.kt" to 4,
        "services/recording/handoff/HandoffSource.kt" to 1,
        "services/recording/handoff/TrackAProbe.kt" to 1,
        "system/diagnostics/MicOpDiagnostics.kt" to 1,
        "system/storage/DriveCopyService.kt" to 1,
        "system/storage/FolderProtection.kt" to 1,
        "system/storage/FreeSpaceMonitor.kt" to 1,
        "system/storage/HideRecordings.kt" to 1,
        "system/storage/RecordingFolderCheck.kt" to 2,
        "system/storage/SyncScheduler.kt" to 1,
        "system/updates/UpdatePackageReplacedReceiver.kt" to 1,
        "transcription/TranscriptionEngine.kt" to 1
    )

    private val root: File = listOf(File("src/main/java/com/baba/callvault"), File("app/src/main/java/com/baba/callvault"))
        .first { it.isDirectory }

    private fun codeLines(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.startsWith("import ") }

    private fun counts(pattern: Regex): Map<String, Int> = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .associate { it.relativeTo(root).path to codeLines(it).sumOf { line -> pattern.findAll(line).count() } }
        .filterValues { it > 0 }

    private fun overBaseline(found: Map<String, Int>, baseline: Map<String, Int>): List<String> =
        found.filter { (file, n) -> n > (baseline[file] ?: 0) }
            .map { (file, n) -> "$file: $n found, ${baseline[file] ?: 0} allowed" }

    @Test
    fun noNewHandMadeCoroutineScopes() {
        val over = overBaseline(counts(scopePattern), scopeBaseline)
        assertTrue("New CoroutineScope(...) without a named owner (see threading-policy.md):\n" + over.joinToString("\n"), over.isEmpty())
    }

    @Test
    fun noNewRunBlocking() {
        val over = overBaseline(counts(blockPattern), blockBaseline)
        assertTrue("New runBlocking (blocks its thread; see threading-policy.md):\n" + over.joinToString("\n"), over.isEmpty())
    }

    @Test
    fun noNewRawThreadsOrExecutors() {
        val over = overBaseline(counts(threadPattern), threadBaseline)
        assertTrue("New raw Thread/HandlerThread/Executor (see threading-policy.md):\n" + over.joinToString("\n"), over.isEmpty())
    }

    @Test
    fun globalScopeIsNeverUsed() {
        val found = counts(globalPattern).keys.toList()
        assertTrue("GlobalScope is banned (no owner, never cancelled): $found", found.isEmpty())
    }

    @Test
    fun baselineIsNotStale() {
        // A baseline above the real count hides a future addition in the same file; keep it tight.
        val stale = listOf(
            scopeBaseline to counts(scopePattern),
            blockBaseline to counts(blockPattern),
            threadBaseline to counts(threadPattern),
        ).flatMap { (base, found) -> base.filter { (file, n) -> n > (found[file] ?: 0) }.map { (file, n) -> "$file: baseline $n, found ${found[file] ?: 0}" } }
        assertTrue("Lower these baselines to the real count:\n" + stale.joinToString("\n"), stale.isEmpty())
    }
}
