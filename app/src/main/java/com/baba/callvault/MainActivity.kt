/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.system.AppLock
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.ui.screens.AppLockScreen
import com.baba.callvault.ui.screens.AppLockUi
import com.baba.callvault.ui.screens.appLockUi

/**
 * MainActivity is the single Android Activity entry point for CallVault.
 * This is called when Android want to show the application UI to the user.
 *
 * It attaches the Compose content tree to the window and draws edge-to-edge (the navy background
 * extends behind the transparent system bars), and it holds the app lock — the one piece of state
 * that has to live at the Activity level, because it is about the window rather than about any screen.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Whether the current visit has been authenticated.
     *
     * Reset in [onStop] rather than `onPause`, and this is the load-bearing detail: the biometric
     * prompt is a dialog, so it pauses the activity without stopping it. Clearing this in `onPause`
     * would re-lock the app the instant the prompt appeared and ask again for ever.
     */
    private var isUnlocked by mutableStateOf(false)

    /** Guards against a second prompt while one is already on screen, for the same reason. */
    private var isPrompting = false

    /**
     * Whether a prompt has come back without authenticating, so the manual way back in is worth
     * offering. Observable, because the window is drawn from it.
     *
     * Starts false on every fresh visit, including after a rotation: the lock card is a recovery
     * route, not a greeting, and showing it before the prompt has even been asked for is what made
     * it flash on open.
     */
    private var promptDismissed by mutableStateOf(false)

    /**
     * What the notification that opened this visit was about, or [NotificationDestination.None].
     *
     * Observable, because the arrival can happen while the window is already up: a tap on a
     * notification while the app is in the background delivers through [onNewIntent], and a state
     * read from the composition is what turns that into a redraw. Cleared once the tree has acted on
     * it, so one tap navigates once.
     *
     * Held here rather than read from the Intent inside Compose because [AppLock] can stand between
     * the two: a destination that arrives at a locked app has to wait for the unlock rather than be
     * lost to it.
     */
    private var notificationDestination by mutableStateOf(NotificationDestination.None)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold start. Both of the PendingIntents that use FLAG_ACTIVITY_CLEAR_TOP arrive this way
        // rather than through onNewIntent whenever the Activity is not already on top, because the
        // launch mode is `standard`: CLEAR_TOP with no SINGLE_TOP destroys the existing instance and
        // builds a new one with the new Intent. Both paths are handled; neither can be assumed.
        notificationDestination = destinationOf(intent)
        // Carry an unlock across an Activity recreation — see [onSaveInstanceState] for why this is
        // only ever set for a configuration change. Read before setContent so the first composition
        // draws the app rather than the lock screen and then swaps.
        isUnlocked = savedInstanceState?.getBoolean(KEY_IS_UNLOCKED) == true
        enableEdgeToEdge()
        setContent {
            when (appLockUi(AppLock.isEnabled(this), isUnlocked, promptDismissed)) {
                AppLockUi.APP -> AppNavigationScreen(
                    notificationDestination = notificationDestination,
                    onNotificationDestinationHandled = {
                        notificationDestination = NotificationDestination.None
                    }
                )
                // Background only. The prompt is coming or already up, so there is nothing to act on
                // — drawing the door here is what flashed an "Unlock" card on every open.
                AppLockUi.WAITING -> AppLockScreen(onUnlock = ::promptForUnlock, showDoor = false)
                // A door rather than a blank screen: the prompt can be dismissed, and someone who
                // dismissed it by accident needs a way back in that is not "kill the app".
                AppLockUi.DOOR -> AppLockScreen(onUnlock = ::promptForUnlock, showDoor = true)
            }
        }
    }

    /**
     * Warm delivery: the Activity was already alive and the system handed it a new Intent instead of
     * building one. The debug reminder takes this path (it asks for SINGLE_TOP), and so does any tap
     * that finds the app already on top.
     *
     * [setIntent] is not optional here. Everything that later asks the Activity what it was started
     * with — including a recreation for a rotation — reads `getIntent()`, and without this it would
     * keep answering with the launch Intent from minutes ago.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDestination = destinationOf(intent)
    }

    /**
     * Reads the requested destination off an incoming Intent.
     *
     * The history flag is passed through rather than checked in Compose: reopening from the recents
     * list re-delivers the Intent the task was started with, extras included, so a single tap on
     * "recording is broken" would otherwise re-navigate on every later return to the app.
     */
    private fun destinationOf(intent: Intent?): NotificationDestination =
        NotificationDestination.fromIntentExtra(
            key = intent?.getStringExtra(NotificationDestination.EXTRA),
            relaunchedFromHistory =
                (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        )

    override fun onStart() {
        super.onStart()
        applySecureFlag()
        if (AppLock.isEnabled(this) && !isUnlocked) promptForUnlock()
    }

    override fun onResume() {
        super.onResume()
        // Anchor the recorder daemon whenever the app is opened. Modern Android requires a foreground
        // context to (re)start a foreground service, so we do it here rather than from Application.onCreate.
        // Idempotent — no-op if the keep-alive service is already running.
        //
        // Deliberately NOT behind the lock: recording is the app's job and must not wait on someone
        // being present to authenticate. The lock hides what was said, it does not stop the recorder.
        if (AppPreferences(applicationContext).isPrivilegedTransportSetUp()) {
            DaemonKeepAliveService.start(applicationContext)
        }
    }

    /**
     * Carries the unlock across an Activity recreation, and ONLY across a recreation.
     *
     * Guarded on [isChangingConfigurations] deliberately. Rotating the phone is not leaving the app,
     * and re-prompting for a fingerprint every time the phone turns in someone's hand is both
     * useless as security and enough to make the lock not worth having. But this same bundle comes
     * back after the process is killed and restored from recents, which very much IS leaving — so
     * nothing is written in that case and [onCreate] reads false, leaving the app locked.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isChangingConfigurations) outState.putBoolean(KEY_IS_UNLOCKED, isUnlocked)
    }

    override fun onStop() {
        super.onStop()
        // Re-lock on the way out, so returning from the recents list asks again — but a rotation is
        // not a way out. Without this guard the recreated Activity's onStart would fire a second
        // biometric prompt before the restored flag above could be of any use.
        if (AppLock.isEnabled(this) && !isChangingConfigurations) {
            isUnlocked = false
            // Cleared with it, so the next visit opens quiet and asks, rather than opening onto a
            // stale door left over from a prompt dismissed last time.
            promptDismissed = false
        }
    }

    /**
     * Keeps the window's content out of screenshots and the recents thumbnail.
     *
     * Part of the same setting rather than a second one: a lock that still shows the last transcript
     * as a thumbnail in the app switcher is not a lock, and nobody who wanted the first would decline
     * the second.
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
        // Asking again hides the door for as long as the prompt is up, so tapping Unlock does not
        // leave the button sitting behind the system sheet.
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
                    // Errors and cancellations both land here, and neither unlocks anything. The
                    // lock screen stays, with its own button, so a mis-tap is recoverable without
                    // this having to tell the two apart.
                    isPrompting = false
                    // This is the ONLY thing that raises the door. Reaching here means the user is
                    // looking at a locked app with no prompt on it, which is the one moment the
                    // button is the difference between getting back in and force-stopping.
                    promptDismissed = true
                }

                override fun onAuthenticationFailed() {
                    // A rejected fingerprint. The prompt is still up and will try again; doing
                    // anything here would only get in its way.
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
        /** Survives an Activity recreation only; never written when the process is going away. */
        const val KEY_IS_UNLOCKED = "is_unlocked"
    }
}
