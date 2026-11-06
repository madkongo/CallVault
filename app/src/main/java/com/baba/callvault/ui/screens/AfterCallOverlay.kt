/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.recording.aftercall.AfterCallDecision
import com.baba.callvault.services.recording.aftercall.AfterCallDecisions
import com.baba.callvault.services.recording.aftercall.AfterCallPolicy
import com.baba.callvault.services.recording.aftercall.AfterCallText
import com.baba.callvault.system.AppLock
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keep-or-delete pop-up ("How to ask: Pop-up over the screen"), drawn as an overlay window.
 *
 * A window and not an Activity: from Android 15, holding the overlay permission no longer lets a background
 * app start an activity (it must already show an overlay), and the start fails silently. Adding a
 * `TYPE_APPLICATION_OVERLAY` window is exactly what the permission grants.
 *
 * It never stands alone: the notification is posted first (see [AfterCallDecision.ask]), so closing this by
 * tapping outside or Back is "not now", never an answer. Answers go through the same [AfterCallDecision]
 * functions as the notification. One window for any number of calls: it asks about the OLDEST waiting
 * recording, moves on after an answer, and follows the store, so an answer given in the shade closes it too.
 * It is removed from the window manager on every way out: nothing left to ask, closed, or answered elsewhere.
 *
 * Main thread only; [show] posts itself there.
 */
internal object AfterCallOverlay {

    private const val TAG = "CV:AfterCall"

    private val main = Handler(Looper.getMainLooper())

    private var window: Window? = null

    /** Opens the window, or refreshes it when it is already open. Never throws; a failure leaves the notification. */
    fun show(context: Context) {
        val app = context.applicationContext
        main.post {
            runCatching { (window ?: Window(app).also { window = it; it.attach() }).refresh() }
                .onFailure {
                    AppLogger.w(TAG, "Could not show the keep-or-delete pop-up; the notification stands: ${it.message}")
                    close()
                }
        }
    }

    /** Whether the window is up. Main thread. */
    internal val isShowing: Boolean get() = window != null

    /** Removes the window. Safe to call when none is shown. */
    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { close() }
            return
        }
        window?.detach()
        window = null
    }

    private class Question(val displayName: String, val subtitle: String)

    /** One shown window: its view, its Compose owners, and the state the card draws. */
    private class Window(private val context: Context) {

        private val windowManager = context.getSystemService(WindowManager::class.java)
        private val owner = OverlayOwner()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var refreshJob: Job? = null

        private var question by mutableStateOf<Question?>(null)
        private var isAnswering by mutableStateOf(false)

        /** When the card last showed a new recording, for the accidental-tap guard. */
        private var shownAtMs = 0L

        /** Held strongly: SharedPreferences keeps listeners only weakly. Called on the main thread. */
        private val storeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

        private val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    AfterCallOverlay.close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }

        fun attach() {
            val compose = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { QuestionCard() }
            }
            root.addView(compose)
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
            root.setViewTreeViewModelStoreOwner(owner)
            owner.start()
            windowManager.addView(root, layoutParams())
            AfterCallDecisions.registerListener(context, storeListener)
        }

        fun detach() {
            AfterCallDecisions.unregisterListener(context, storeListener)
            scope.cancel()
            runCatching { windowManager.removeView(root) }
                .onFailure { AppLogger.w(TAG, "Could not remove the keep-or-delete pop-up: ${it.message}") }
            owner.stop()
        }

        /** Re-reads which recording to ask about. Only the latest read may land, so a slow one cannot show a stale card. */
        fun refresh() {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                val next = withContext(Dispatchers.IO) {
                    runCatching {
                        AfterCallDecisions.nextToAsk(context)?.let { held ->
                            Question(held.displayName, AfterCallText.popupSubtitle(context, held.displayName, AfterCallText.durationOf(context, held)))
                        }
                    }.getOrNull()
                }
                if (next == null) {
                    AfterCallOverlay.close()
                    return@launch
                }
                if (next.displayName != question?.displayName) shownAtMs = SystemClock.elapsedRealtime()
                question = next
            }
        }

        private fun answer(displayName: String, keep: Boolean) {
            if (isAnswering) return
            if (!AfterCallPolicy.acceptsTap(shownAtMs, SystemClock.elapsedRealtime())) return
            isAnswering = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (keep) AfterCallDecision.keep(context, displayName) else AfterCallDecision.delete(context, displayName)
                        }.onFailure { AppLogger.e(TAG, "The pop-up answer could not be applied", it) }
                    }
                } finally {
                    isAnswering = false
                }
                refresh()
            }
        }

        // Not named Content: inside ComposeView.setContent that name resolves to ComposeView's own Content().
        @androidx.compose.runtime.Composable
        private fun QuestionCard() {
            val preferences = remember { AppPreferences(context) }
            val themeMode = remember { preferences.getThemeMode() }
            val dynamicColor = remember { preferences.isDynamicColorEnabled() }
            val darkTheme = when (themeMode) {
                AppPreferences.ThemeMode.LIGHT -> false
                AppPreferences.ThemeMode.DARK -> true
                AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            CallVaultTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                val shown = question ?: return@CallVaultTheme
                AfterCallQuestionScreen(
                    subtitle = shown.subtitle,
                    isAnswering = isAnswering,
                    onKeep = { answer(shown.displayName, keep = true) },
                    onDelete = { answer(shown.displayName, keep = false) },
                    onDismiss = { AfterCallOverlay.close() },
                )
            }
        }

        /**
         * Full screen, so a tap outside the card lands on our dimmed area and closes it; focusable, so Back
         * reaches us. Secure when the app lock forbids screenshots, as the app's own windows are.
         */
        private fun layoutParams(): WindowManager.LayoutParams {
            val secure = AppLock.isEnabled(context) && !AppPreferences(context).isAllowScreenshots()
            @SuppressLint("InlinedApi")
            val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0)
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            )
        }
    }

    /** The minimum Compose needs from a host that is not an Activity: a lifecycle, saved state, a ViewModel store. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
