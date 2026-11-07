/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.AppPreferences.AfterCallAskStyle
import com.baba.callvault.data.AppPreferences.AfterCallMode
import com.baba.callvault.data.AppPreferences.AfterCallUnanswered
import com.baba.callvault.services.recording.aftercall.AfterCallNotifier
import com.baba.callvault.services.recording.aftercall.AfterCallSettings
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.common.rememberResumeCount
import com.baba.callvault.utils.AppLogger

private const val TAG = "CV:AfterCallSettings"

/**
 * Turning "Ask me: keep or delete" on, only once the question can actually be shown.
 *
 * A user must never end up in Ask with questions that cannot appear (their recordings would wait unseen), so
 * [request] switches it on at once when notifications work and otherwise asks for them: the runtime
 * permission on Android 13+, else the app's (or, when only our channel is blocked, the channel's) notification
 * settings. The setting stays on Keep meanwhile; when the user comes back with notifications allowed, it
 * switches on by itself. [isWaiting] says a request is open, for the one-line note under the control; [cancel]
 * drops it, so a user who chose Keep in the meantime is not switched to Ask behind their back on return.
 */
internal class AfterCallAskEnabler(val request: () -> Unit, val cancel: () -> Unit, val isWaiting: Boolean)

@Composable
internal fun rememberAfterCallAskEnabler(onAllowed: () -> Unit): AfterCallAskEnabler {
    val context = LocalContext.current
    var isWaiting by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!isWaiting) return@rememberLauncherForActivityResult
        if (AfterCallNotifier.canAsk(context)) {
            isWaiting = false
            onAllowed()
        } else {
            openNotificationSettings(context)
        }
    }
    val resumes = rememberResumeCount()
    LaunchedEffect(resumes) {
        if (isWaiting && AfterCallNotifier.canAsk(context)) {
            isWaiting = false
            onAllowed()
        }
    }
    return AfterCallAskEnabler(
        request = request@{
            if (AfterCallNotifier.canAsk(context)) {
                onAllowed()
                return@request
            }
            isWaiting = true
            val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsRuntimePermission) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings(context)
            }
        },
        cancel = { isWaiting = false },
        isWaiting = isWaiting,
    )
}

/** The app's notification settings, or our channel's when the app's are on and only the channel is blocked. */
private fun openNotificationSettings(context: Context) {
    val appOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val intent = if (appOn) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AfterCallNotifier.CHANNEL_ID)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }
    runCatching {
        context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { AppLogger.w(TAG, "Could not open the notification settings: ${it.message}") }
}

/**
 * "When a call ends": Keep the recording (as always) / Ask me: keep or delete. See
 * docs/dev-notes/2026-11-06-keep-or-delete-plan.md. [onAskChange] lets the group fold the two Ask-only rows
 * in and out with it.
 */
@Composable
internal fun AfterCallModeRow(onAskChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var mode by remember { mutableStateOf(prefs.getAfterCallMode()) }
    val choose: (AfterCallMode) -> Unit = { m ->
        AfterCallSettings.setMode(context, m)
        mode = m
        onAskChange(m == AfterCallMode.ASK)
    }
    val enabler = rememberAfterCallAskEnabler(onAllowed = { choose(AfterCallMode.ASK) })
    val options = listOf(
        OptionItem(AfterCallMode.KEEP.key, stringResource(R.string.settings_after_call_mode_keep)),
        OptionItem(AfterCallMode.ASK.key, stringResource(R.string.settings_after_call_mode_ask)),
    )
    val explanation = listOf(
        R.string.settings_after_call_mode_keep to R.string.settings_after_call_mode_keep_desc,
        R.string.settings_after_call_mode_ask to R.string.settings_after_call_mode_ask_desc,
    ).map { (name, desc) -> stringResource(name) + ": " + stringResource(desc) }.joinToString("\n\n")
    DropdownRow {
        WithInfo(stringResource(R.string.settings_after_call_mode_label), explanation) {
            M3DropdownField(
                label = stringResource(R.string.settings_after_call_mode_label),
                selected = options.first { it.key == mode.key },
                options = options,
                onOptionSelected = { picked ->
                    when (AfterCallMode.fromKey(picked.key)) {
                        AfterCallMode.ASK -> enabler.request()
                        AfterCallMode.KEEP -> {
                            enabler.cancel()
                            choose(AfterCallMode.KEEP)
                        }
                    }
                },
            )
        }
    }
    if (enabler.isWaiting && mode != AfterCallMode.ASK) SettingsHint(stringResource(R.string.settings_after_call_needs_notifications))
}

/**
 * "How to ask": Notification / Pop-up over the screen. Pop-up is shown as chosen only while the overlay
 * permission is granted. Choosing it without the permission first says what the permission is for and sends
 * the user to Android's page ([rememberPopUpPermissionRequest]); it switches on by itself on return with the
 * permission granted. Until then, and whenever the permission is taken away later, it reads Notification with
 * a line saying why.
 */
@Composable
internal fun AfterCallAskStyleRow() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var stored by remember { mutableStateOf(prefs.getAfterCallAskStyle()) }
    val permission = rememberPopUpPermissionRequest(onAllowed = {
        prefs.setAfterCallAskStyle(AfterCallAskStyle.POPUP)
        stored = AfterCallAskStyle.POPUP
    })
    val shown = if (stored == AfterCallAskStyle.POPUP && permission.isGranted) AfterCallAskStyle.POPUP else AfterCallAskStyle.NOTIFICATION
    val options = listOf(
        OptionItem(AfterCallAskStyle.NOTIFICATION.key, stringResource(R.string.settings_after_call_style_notification)),
        OptionItem(AfterCallAskStyle.POPUP.key, stringResource(R.string.settings_after_call_style_popup)),
    )
    val explanation = listOf(
        R.string.settings_after_call_style_notification to R.string.settings_after_call_style_notification_desc,
        R.string.settings_after_call_style_popup to R.string.settings_after_call_style_popup_desc,
    ).map { (name, desc) -> stringResource(name) + ": " + stringResource(desc) }.joinToString("\n\n")
    DropdownRow {
        WithInfo(stringResource(R.string.settings_after_call_style_label), explanation) {
            M3DropdownField(
                label = stringResource(R.string.settings_after_call_style_label),
                selected = options.first { it.key == shown.key },
                options = options,
                onOptionSelected = { picked ->
                    when (AfterCallAskStyle.fromKey(picked.key)) {
                        AfterCallAskStyle.POPUP -> permission.request()
                        AfterCallAskStyle.NOTIFICATION -> {
                            permission.cancel()
                            prefs.setAfterCallAskStyle(AfterCallAskStyle.NOTIFICATION)
                            stored = AfterCallAskStyle.NOTIFICATION
                        }
                    }
                },
            )
        }
    }
    val wantsPopUp = permission.isWaiting || stored == AfterCallAskStyle.POPUP
    if (wantsPopUp && !permission.isGranted) SettingsHint(stringResource(R.string.settings_after_call_needs_overlay))
}

/** "If I don't answer": Keep it / Delete it after 24 hours. Applied by the 24-hour check when it runs. */
@Composable
internal fun AfterCallUnansweredRow() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var choice by remember { mutableStateOf(prefs.getAfterCallUnanswered()) }
    val options = listOf(
        OptionItem(AfterCallUnanswered.KEEP.key, stringResource(R.string.settings_after_call_unanswered_keep)),
        OptionItem(AfterCallUnanswered.DELETE_AFTER_24H.key, stringResource(R.string.settings_after_call_unanswered_delete)),
    )
    val explanation = (
        listOf(
            R.string.settings_after_call_unanswered_keep to R.string.settings_after_call_unanswered_keep_desc,
            R.string.settings_after_call_unanswered_delete to R.string.settings_after_call_unanswered_delete_desc,
        ).map { (name, desc) -> stringResource(name) + ": " + stringResource(desc) } +
            stringResource(R.string.settings_after_call_unanswered_engaged)
        ).joinToString("\n\n")
    DropdownRow {
        WithInfo(stringResource(R.string.settings_after_call_unanswered_label), explanation) {
            M3DropdownField(
                label = stringResource(R.string.settings_after_call_unanswered_label),
                selected = options.first { it.key == choice.key },
                options = options,
                onOptionSelected = { picked ->
                    val c = AfterCallUnanswered.fromKey(picked.key)
                    prefs.setAfterCallUnanswered(c)
                    choice = c
                },
            )
        }
    }
}
