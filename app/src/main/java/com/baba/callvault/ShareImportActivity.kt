/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.AppLock
import com.baba.callvault.ui.navigation.OpenRecordingRequest
import com.baba.callvault.ui.screens.AppLockScreen
import com.baba.callvault.ui.screens.AppLockUi
import com.baba.callvault.ui.screens.ShareImportScreen
import com.baba.callvault.ui.screens.appLockUi
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.ui.viewmodels.ShareImportViewModel
import com.baba.callvault.utils.AppLogger

/**
 * Where an audio file shared from another app arrives.
 *
 * ## Why this is not `MainActivity`
 *
 * Three reasons, and each of them is a way the share would otherwise be lost:
 *
 * 1. **`MainActivity` reads no `EXTRA_STREAM` and never has.** It would open the app and drop the
 *    file on the floor, with nothing anywhere saying a file had been shared at all.
 * 2. **It routes an unfinished setup into the wizard.** A share arriving before onboarding is done
 *    would dump the user into a setup flow they did not ask for, having silently discarded what they
 *    shared. Here, [SharedAudio][com.baba.callvault.data.recordings.SharedAudio] says so out loud
 *    instead.
 * 3. **Its launch mode is `standard`.** A share while the app was already open would build a second
 *    instance of the whole app over the first — a second recordings list, a second player — for a
 *    file that needs none of it.
 *
 * ## The lock
 *
 * This is audio out of somebody's private messages, and the same lock stands in front of it as
 * stands in front of the transcripts. The gate is `MainActivity`'s, reproduced deliberately rather
 * than referenced: `isUnlocked` reset in `onStop` and not `onPause` (the biometric prompt is a
 * dialog, so it pauses without stopping, and clearing it on pause would re-prompt for ever),
 * `isChangingConfigurations` so a rotation is not a departure, and `FLAG_SECURE` so the file name
 * does not survive in the recents thumbnail.
 *
 * **Nothing is read, copied or catalogued until the lock is satisfied.** The import is started from
 * inside the unlocked branch of the composition, so a locked phone handed to someone else cannot be
 * used to put a file into the library — or, more to the point, to find out what is already in it.
 */
class ShareImportActivity : AppCompatActivity() {

    private val viewModel: ShareImportViewModel by viewModels()

    /** Whether this visit has been authenticated. See the class KDoc for why `onStop` clears it. */
    private var isUnlocked by mutableStateOf(false)

    /** Guards against a second prompt while one is already on screen. */
    private var isPrompting = false

    /** Whether a prompt came back without authenticating, so the manual way in is worth offering. */
    private var promptDismissed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isUnlocked = savedInstanceState?.getBoolean(KEY_IS_UNLOCKED) == true

        // Read once, here, rather than from the composition: onNewIntent is not registered for this
        // Activity and the Intent cannot change under it, and a share that survives a rotation has
        // to be the same share it started as.
        val action = intent?.action
        val source = streamUri()
        AppLogger.i(TAG, "Share received: action=$action, type=${intent?.type}, stream=${source != null}")

        setContent {
            val preferences = AppPreferences(this)
            val darkTheme = when (preferences.getThemeMode()) {
                AppPreferences.ThemeMode.LIGHT -> false
                AppPreferences.ThemeMode.DARK -> true
                AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            when (appLockUi(AppLock.isEnabled(this), isUnlocked, promptDismissed)) {
                AppLockUi.APP -> CallVaultTheme(
                    darkTheme = darkTheme,
                    dynamicColor = preferences.isDynamicColorEnabled(),
                ) {
                    // The one place the work starts. Behind the lock by construction, and guarded
                    // against a second run inside the ViewModel, since a rotation or the lock
                    // resolving both re-enter this composition.
                    LaunchedEffect(Unit) { viewModel.begin(action, source) }

                    val state by viewModel.state.collectAsState()
                    ShareImportScreen(
                        state = state,
                        // The imported file's own screen, which is where its length, its player and
                        // Transcribe already are — the same place the picker lands. Without the
                        // name, Open landed on whichever section the user was last in, which on a
                        // real run was a page of summaries with no sign of what had just arrived.
                        onOpenRecording = { name -> openApp(name); finish() },
                        onOpenApp = { openApp(recording = null); finish() },
                        onClose = { finish() },
                    )
                }
                // Background only — the prompt is on its way, and drawing the door under it is what
                // flashed an "Unlock" card on every open of the app itself.
                AppLockUi.WAITING -> AppLockScreen(onUnlock = ::promptForUnlock, showDoor = false)
                AppLockUi.DOOR -> AppLockScreen(onUnlock = ::promptForUnlock, showDoor = true)
            }
        }
    }

    /**
     * The shared file's URI, or null.
     *
     * Wrapped, because this Activity is `exported="true"` — it has to be, or the share sheet cannot
     * reach it — so any app on the phone can hand it an extra of the wrong class or a deliberately
     * malformed Parcel, and `getParcelableExtra` throws on both. A share target that crashes on a
     * bad extra is a share target that crashes on demand.
     */
    private fun streamUri(): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
    }.getOrElse { e ->
        AppLogger.w(TAG, "Unreadable EXTRA_STREAM: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * Opens CallVault proper, as a fresh task, so backing out of it does not return to this card.
     *
     * [recording] names a row to land on, or is null to open the app wherever it was last. CLEAR_TOP
     * without SINGLE_TOP is deliberate and matches how the notifications reach MainActivity: its
     * launch mode is `standard`, so this rebuilds the Activity with the new Intent rather than
     * handing it to an instance that has already read one.
     */
    private fun openApp(recording: String?) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { recording?.let { putExtra(OpenRecordingRequest.EXTRA, it) } }
        )
    }

    override fun onStart() {
        super.onStart()
        applySecureFlag()
        if (AppLock.isEnabled(this) && !isUnlocked) promptForUnlock()
    }

    /** Carries the unlock across a rotation, and only across a rotation. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isChangingConfigurations) outState.putBoolean(KEY_IS_UNLOCKED, isUnlocked)
    }

    override fun onStop() {
        super.onStop()
        // Re-lock on the way out. Without the configuration guard the recreated Activity's onStart
        // would fire a second biometric prompt before the restored flag could be of any use.
        if (AppLock.isEnabled(this) && !isChangingConfigurations) {
            isUnlocked = false
            promptDismissed = false
        }

        // A share card that is off screen has nothing left to say, and leaving it alive costs the
        // NEXT share. Measured: `am start` of a second SEND while the first card was still up came
        // back START_DELIVERED_TO_TOP and the new Intent was dropped on the floor, because
        // FLAG_ACTIVITY_NEW_TASK reuses a task whose root Intent `filterEquals` the incoming one
        // and Intent.filterEquals compares action, type and component but NOT extras — so two
        // shares of two entirely different files are, to the system, the same Intent. A share
        // silently doing nothing is the worst outcome this screen has.
        //
        // Never while a copy is running: finishing takes the ViewModel with it, which would cancel
        // the job between the copy and the catalogue and leave a file in the user's folder that
        // the app has no record of. A share behind the app lock has copied nothing, so it is not
        // busy and does not hold the card open — see ShareImportViewModel.isBusy.
        if (!isChangingConfigurations && !isFinishing && !viewModel.isBusy) finish()
    }

    /**
     * Keeps the shared file's name out of screenshots and the recents thumbnail.
     *
     * Same setting, same reasoning as the app's own window: a lock that leaves "PTT-20260916-WA0003"
     * legible in the app switcher has given away most of what the lock was for.
     */
    private fun applySecureFlag() {
        if (AppLock.isEnabled(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun promptForUnlock() {
        if (isPrompting) return
        isPrompting = true
        promptDismissed = false

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isPrompting = false
                    isUnlocked = true
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Errors and cancellations both land here and neither unlocks anything. The
                    // door is what makes a mis-tap recoverable without force-stopping the app.
                    isPrompting = false
                    promptDismissed = true
                }

                override fun onAuthenticationFailed() {
                    // A rejected fingerprint; the prompt is still up and will try again.
                }
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock_prompt_title))
                .setAllowedAuthenticators(AppLock.allowedAuthenticators())
                .build()
        )
    }

    private companion object {
        const val TAG = "CV:ShareImport"

        /** Survives an Activity recreation only; never written when the process is going away. */
        const val KEY_IS_UNLOCKED = "is_unlocked"
    }
}
