package com.baba.callvault.system

import com.baba.callvault.integrations.adb.AdbPairingService
import com.baba.callvault.services.boot.AdbConnectionService
import com.baba.callvault.services.debug.DebugNotificationHelper
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.services.recording.SharedStatusNotice
import com.baba.callvault.services.recording.VoipRecordPrompt
import com.baba.callvault.services.recording.VoipRecordingNotification
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.system.storage.FolderNotice
import com.baba.callvault.system.storage.FreeSpaceMonitor
import com.baba.callvault.system.updates.UpdateNotifications
import com.baba.callvault.transcription.HeavyWorkNotification
import com.baba.callvault.transcription.TranscriptNotifier
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review C36: two features posting and cancelling the same id wipe each other's notices. */
class NotificationIdsTest {

    @Test
    fun `no two notification ids of the app are the same`() {
        val named = mapOf(
            "RecordingNotificationHelper.SERVICE" to RecordingNotificationHelper.SERVICE_NOTIFICATION_ID,
            "RecordingNotificationHelper.ERROR" to RecordingNotificationHelper.ERROR_NOTIFICATION_ID,
            "VoipRecordPrompt" to VoipRecordPrompt.NOTIFICATION_ID,
            "VoipRecordingNotification.LEGACY" to VoipRecordingNotification.LEGACY_NOTIFICATION_ID,
            "HeavyWorkNotification" to HeavyWorkNotification.NOTIFICATION_ID,
            "HeavyWorkNotification.DRIVE_COPY" to HeavyWorkNotification.DRIVE_COPY_NOTIFICATION_ID,
            "DebugNotificationHelper" to DebugNotificationHelper.NOTIFICATION_ID,
            "AdbPairingService" to AdbPairingService.NOTIFICATION_ID,
            "AdbConnectionService" to AdbConnectionService.NOTIF_ID,
            "SharedStatusNotice" to SharedStatusNotice.ID,
            "TranscriptNotifier.READY" to TranscriptNotifier.ID_READY,
            "TranscriptNotifier.FAILED" to TranscriptNotifier.ID_FAILED,
            "RecordingNotificationHelper.BATTERY_EXEMPTION" to RecordingNotificationHelper.BATTERY_EXEMPTION_NOTIFICATION_ID,
            "FolderNotice" to FolderNotice.NOTIFICATION_ID,
            "FreeSpaceMonitor" to FreeSpaceMonitor.NOTIFICATION_ID,
        )
        val all = named.entries.map { it.key to it.value } +
            SilentFailureNotifier.ALL_IDS.map { "SilentFailureNotifier" to it } +
            UpdateNotifications.ALL_IDS.map { "UpdateNotifications" to it }
        val duplicates = all.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }
        assertEquals("ids used twice: $duplicates", emptyMap<Int, List<String>>(), duplicates)
    }
}
