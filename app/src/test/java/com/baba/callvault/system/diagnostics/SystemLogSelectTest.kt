/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import com.baba.callvault.system.diagnostics.SystemLogFilter.Ours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system half of a report has to say WHY Android stopped us, not only that it did. A vivo Android 17 report
 * (2026-11-20) showed `CannotPostForegroundServiceNotificationException: Bad notification for startForeground` three
 * times and nothing from system_server around it, so the cause had to be read out of code. These are the lines that
 * would have said it — and, next to each, the same kind of line about another app, which must still be dropped.
 *
 * Line shapes follow AOSP's own Slog / EventLog output (ActiveServices, ServiceRecord, AppErrors, ProcessList).
 */
class SystemLogSelectTest {

    private val ours = Ours(packageName = "com.baba.callvault", uid = 10024)

    private fun select(vararg lines: String) = SystemLogFilter.select(lines.toList(), ours)

    // Our own process, seen through our own line: pid 13572 is ours from 17:40 to 17:48.
    private val ownLine = "11-20 17:48:48.306 13572 13600 I CV:VoipRec: The app call ended before the recording got going"

    @Test
    fun `keeps the system_server lines that explain a bad foreground notification`() {
        val serviceRecord = listOf(
            "11-20 17:48:48.310  1500  1530 W ActivityManager: Error showing notification for service",
            "11-20 17:48:48.310  1500  1530 W ActivityManager: java.lang.RuntimeException: invalid channel for service notification: Notification(channel=recorder_status shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x40 color=0x00000000 vis=PRIVATE)",
            "11-20 17:48:48.310  1500  1530 W ActivityManager: \tat com.android.server.am.ServiceRecord\$1.run(ServiceRecord.java:1342)",
            "11-20 17:48:48.310  1500  1530 W ActivityManager: \tat android.os.Handler.handleCallback(Handler.java:991)",
        )
        val crash = "11-20 17:48:48.311  1500  1530 W ActivityManager: Crashing app com.baba.callvault: Bad notification for startForeground"

        val kept = select(ownLine, *serviceRecord.toTypedArray(), crash)

        serviceRecord.forEach { assertTrue("dropped: $it", it in kept) }
        assertTrue(crash in kept)
    }

    @Test
    fun `drops the same notification error when it belongs to another app`() {
        // Nothing of ours died near it, so the channel name it prints is somebody else's.
        val other = listOf(
            "11-20 09:00:00.000  1500  1530 W ActivityManager: Error showing notification for service",
            "11-20 09:00:00.000  1500  1530 W ActivityManager: java.lang.RuntimeException: invalid channel for service notification: Notification(channel=chat_sync)",
            "11-20 09:00:00.001  1500  1530 W ActivityManager: Crashing app com.example.chat: Bad notification for startForeground",
        )
        val kept = select(ownLine, *other.toTypedArray())
        other.forEach { assertFalse("leaked: $it", it in kept) }
    }

    @Test
    fun `keeps lifecycle lines naming our package from the system tags`() {
        val lines = listOf(
            "11-20 17:48:48.400  1500  1600 I ActivityManager: Process com.baba.callvault (pid 13572) has died: fg  FGS ",
            "11-20 17:48:48.401  1500  1600 I ActivityManager: Killing 13572:com.baba.callvault/u0a24 (adj 0): crash",
            "11-20 17:48:48.402  1500  1600 I ActivityManager: Force stopping com.baba.callvault appid=10024 user=0: clear data",
            "11-20 17:48:48.403  1500  1600 E ActivityManager: ANR in com.baba.callvault:drive",
            "11-20 17:48:48.404  1500  1600 W ActivityTaskManager: Force removing ActivityRecord{1 u0 com.baba.callvault/.MainActivity t9}: app died",
            "11-20 17:48:48.405  1500  1600 W NotificationService: Ignoring notification for package com.baba.callvault",
            "11-20 17:48:48.406  1500  1600 W JobScheduler: Job didn't exist in JobStore: 5d1 #u0a24/7 com.baba.callvault/androidx.work.impl.background.systemjob.SystemJobService",
            "11-20 17:48:48.407  1500  1600 I DeviceIdleController: removePowerSaveWhitelistApp(name = com.baba.callvault)",
            "11-20 17:48:48.408  1500  1600 I AppOps  : Noting op 27 for uid 10024 denied",
            "11-20 17:48:48.409  1500  1600 I PackageManager: Package com.baba.callvault codePath changed",
            "11-20 17:48:48.410  1500  1600 I am_crash: [13572,0,com.baba.callvault,550026820,android.app.RemoteServiceException\$CannotPostForegroundServiceNotificationException,Bad notification for startForeground,ActivityThread.java,3106]",
            "11-20 17:48:48.411  1500  1600 I am_proc_died: [0,13572,com.baba.callvault,0,2]",
            "11-20 17:48:48.412  1500  1600 I am_kill: [0,13572,com.baba.callvault:recorder,0,crash]",
        )
        val kept = select(*lines.toTypedArray())
        lines.forEach { assertTrue("dropped: $it", it in kept) }
    }

    @Test
    fun `keeps OEM process killers only when they name us`() {
        val oursKilled = listOf(
            "11-20 17:00:00.000  1500  1600 I VivoProcessManager: kill com.baba.callvault reason=single-cleaner",
            "11-20 17:00:00.001  1500  1600 I Hans    : freeze uid 10024 com.baba.callvault",
            "11-20 17:00:00.002  1500  1600 I athena  : athena kill pid 13572 com.baba.callvault",
            "11-20 17:00:00.003  1500  1600 I PowerKeeper: kill com.baba.callvault for power saving",
        )
        val othersKilled = listOf(
            "11-20 17:00:00.004  1500  1600 I VivoProcessManager: kill com.whatsapp reason=single-cleaner",
            "11-20 17:00:00.005  1500  1600 I Hans    : freeze uid 10333 com.instagram.android",
        )
        val kept = select(*(oursKilled + othersKilled).toTypedArray())
        oursKilled.forEach { assertTrue("dropped: $it", it in kept) }
        othersKilled.forEach { assertFalse("leaked: $it", it in kept) }
    }

    @Test
    fun `matches the package and uid exactly, not as a prefix or a longer number`() {
        val lookalikes = listOf(
            "11-20 17:00:00.000  1500  1600 I ActivityManager: Process com.baba.callvaultpro (pid 9) has died",
            "11-20 17:00:00.001  1500  1600 I ActivityManager: Process xcom.baba.callvault (pid 9) has died",
            "11-20 17:00:00.002  1500  1600 I AppOps  : Noting op 27 for uid 100241 denied",
            "11-20 17:00:00.003  1500  1600 I ActivityManager: Killing 9:com.other/u0a245 (adj 900): empty",
        )
        val kept = select(*lookalikes.toTypedArray())
        lookalikes.forEach { assertFalse("leaked: $it", it in kept) }
    }

    @Test
    fun `keeps any line our own process wrote, including the runtime's crash`() {
        val fatal = listOf(
            "11-20 17:48:48.313 13572 13572 E AndroidRuntime: FATAL EXCEPTION: main",
            "11-20 17:48:48.313 13572 13572 E AndroidRuntime: Process: com.baba.callvault, PID: 13572",
            "11-20 17:48:48.313 13572 13572 E AndroidRuntime: android.app.RemoteServiceException\$CannotPostForegroundServiceNotificationException: Bad notification for startForeground",
            "11-20 17:48:48.313 13572 13572 E AndroidRuntime: \tat android.app.ActivityThread.throwRemoteServiceException(ActivityThread.java:3106)",
            "11-20 17:48:48.320 13572 13572 I Process : Sending signal. PID: 13572 SIG: 9",
        )
        val kept = select(ownLine, *fatal.toTypedArray())
        fatal.forEach { assertTrue("dropped: $it", it in kept) }
    }

    @Test
    fun `a pid of ours is not trusted long after we last used it`() {
        // Pids are reused. An hour later the same number belongs to whatever app got it next.
        val reused = "11-20 18:50:00.000 13572 13572 I Chat    : message from +972 50-123-4567"
        assertFalse(reused in select(ownLine, reused))
    }

    @Test
    fun `a pid known from Android's exit record is trusted just before that exit`() {
        // A crash with no CV line of its own (it died while starting) is still ours by Android's own record.
        val exitAt = SystemLogFilter.logClockMillis("11-20 17:55:59.000")!!
        val withExit = Ours(
            packageName = "com.baba.callvault",
            uid = 10024,
            pidWindows = listOf(SystemLogFilter.PidWindow(20001, exitAt - 120_000, exitAt + 5_000)),
        )
        val line = "11-20 17:55:58.900 20001 20001 E AndroidRuntime: FATAL EXCEPTION: main"
        val before = "11-20 17:30:00.000 20001 20001 I Other   : not ours yet"
        val kept = SystemLogFilter.select(listOf(line, before), withExit)
        assertEquals(listOf(line), kept)
    }

    @Test
    fun `keeps everything the plain whitelist already kept`() {
        val audio = "07-30 17:46:12.001  1123  1200 I AudioFlinger: rec update uid:2000 src:MIC silenced pack:com.android.shell"
        assertTrue(audio in select(audio))
    }

    @Test
    fun `never keeps another app's lines just because the tag is a system one`() {
        val lines = listOf(
            "11-20 17:00:00.000  1500  1600 I ActivityManager: Start proc 4242:com.whatsapp/u0a99 for service",
            "11-20 17:00:00.001  1500  1600 W NotificationService: Toast already killed. pkg=com.whatsapp",
            "11-20 17:00:00.002  1500  1600 I am_kill: [0,4242,com.whatsapp,900,empty]",
        )
        val kept = select(ownLine, *lines.toTypedArray())
        lines.forEach { assertFalse("leaked: $it", it in kept) }
    }

    @Test
    fun `merges two dumps in time order without repeating a line`() {
        val a = listOf("11-20 17:00:00.000  1 1 I x: a", "11-20 17:00:02.000  1 1 I x: c")
        val b = listOf("11-20 17:00:01.000  1 1 I x: b", "11-20 17:00:02.000  1 1 I x: c")
        assertEquals(
            listOf("11-20 17:00:00.000  1 1 I x: a", "11-20 17:00:01.000  1 1 I x: b", "11-20 17:00:02.000  1 1 I x: c"),
            SystemLogFilter.mergeByTime(a, b),
        )
    }
}
