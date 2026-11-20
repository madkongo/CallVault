package com.baba.callvault

import android.app.Application
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.system.storage.FolderProtection
import com.baba.callvault.system.storage.RecordingFolderCheck

/**
 * The application Robolectric uses in unit tests (app/src/test/resources/robolectric.properties). Phones use
 * CallVaultApplication; this class is never in the app.
 *
 * CallVaultApplication.onCreate starts background threads (folder check, phone profile, schedulers) that outlive their
 * test, and under Robolectric a SharedPreferences write on such a thread waits for ever, holding the locks later tests
 * need (N27). So this one starts no threads; app-start behaviour is therefore NOT run in unit tests. It only wires the two hooks tests rely on, the same way the app does.
 */
class TestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingFolderCheck.onChecked = FolderProtection::onChecked
        RecorderConnection.onDaemonReady = { onDaemonReady(applicationContext) }
    }
}
