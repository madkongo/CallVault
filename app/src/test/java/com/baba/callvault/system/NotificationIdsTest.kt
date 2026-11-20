package com.baba.callvault.system

import com.baba.callvault.services.recording.SharedStatusNotice
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.system.storage.FolderNotice
import com.baba.callvault.system.updates.UpdateNotifications
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review C36: two features posting and cancelling the same id wipe each other's notices. */
class NotificationIdsTest {

    @Test
    fun `no two notification ids of the app are the same`() {
        val ids = SilentFailureNotifier.ALL_IDS + UpdateNotifications.ALL_IDS +
            listOf(SharedStatusNotice.ID, FolderNotice.NOTIFICATION_ID)
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals("ids used twice: $duplicates", emptySet<Int>(), duplicates)
    }
}
