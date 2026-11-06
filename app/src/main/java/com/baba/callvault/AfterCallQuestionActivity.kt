/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.recording.aftercall.AfterCallDecision
import com.baba.callvault.services.recording.aftercall.AfterCallDecisions
import com.baba.callvault.services.recording.aftercall.AfterCallText
import com.baba.callvault.system.AppLock
import com.baba.callvault.ui.screens.AfterCallQuestionScreen
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keep-or-delete pop-up ("How to ask: Pop-up over the screen"): a small window over whatever is on screen
 * when a recorded call ends.
 *
 * It never stands alone. The notification is always posted too (see [AfterCallDecision.ask]), so closing this
 * by tapping outside or Back is "not now", never an answer — the question stays in the shade. Answers go
 * through the same [AfterCallDecision] functions the notification uses, and the window follows the store:
 * when the notification answers first, or the last waiting recording is answered here, it closes itself.
 *
 * One window for any number of calls (`singleInstance`): it always asks about the OLDEST waiting recording,
 * and after an answer moves on to the next one. Not shown over the lock screen; [AfterCallDecision.ask] does
 * not open it while the phone is locked, and the activity does not ask to be shown when locked.
 */
class AfterCallQuestionActivity : AppCompatActivity() {

    /** The recording on screen and its subtitle; null while the first one is being read. */
    private var shown by mutableStateOf<Question?>(null)

    /** True while an answer is being applied, so a double tap cannot answer twice. */
    private var isAnswering by mutableStateOf(false)

    /** Held strongly here: SharedPreferences keeps its listeners only weakly. */
    private val storeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private data class Question(val displayName: String, val subtitle: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlag()
        setContent {
            val preferences = AppPreferences(this)
            val darkTheme = when (preferences.getThemeMode()) {
                AppPreferences.ThemeMode.LIGHT -> false
                AppPreferences.ThemeMode.DARK -> true
                AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            CallVaultTheme(darkTheme = darkTheme, dynamicColor = preferences.isDynamicColorEnabled()) {
                val question = shown
                if (question != null) {
                    AfterCallQuestionScreen(
                        subtitle = question.subtitle,
                        isAnswering = isAnswering,
                        onKeep = { answer(question.displayName, keep = true) },
                        onDelete = { answer(question.displayName, keep = false) },
                        onDismiss = { finish() },
                    )
                }
            }
        }
        AfterCallDecisions.registerListener(this, storeListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        AfterCallDecisions.unregisterListener(this, storeListener)
        super.onDestroy()
    }

    /** Re-reads which recording to ask about; closes when none is waiting any more. */
    private fun refresh() {
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    AfterCallDecisions.nextToAsk(applicationContext)?.let { held ->
                        Question(held.displayName, AfterCallText.popupSubtitle(applicationContext, held.displayName, AfterCallText.durationOf(applicationContext, held)))
                    }
                }.getOrNull()
            }
            if (next == null) {
                finish()
                return@launch
            }
            if (next.displayName != shown?.displayName) isAnswering = false
            shown = next
        }
    }

    private fun answer(displayName: String, keep: Boolean) {
        if (isAnswering) return
        isAnswering = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (keep) AfterCallDecision.keep(applicationContext, displayName) else AfterCallDecision.delete(applicationContext, displayName)
                }.onFailure { AppLogger.e(TAG, "The pop-up answer could not be applied", it) }
            }
            // The store listener usually got here first; this covers a store that did not change (already answered).
            refresh()
        }
    }

    /** As in the share screen: the app lock's secure window, unless the owner allowed screenshots. */
    private fun applySecureFlag() {
        val secure = AppLock.isEnabled(this) && !AppPreferences(this).isAllowScreenshots()
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        private const val TAG = "CV:AfterCall"

        /** Opens (or brings back) the one pop-up window. Called only from the call-end ask. */
        fun open(context: Context) {
            context.startActivity(
                Intent(context, AfterCallQuestionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}
