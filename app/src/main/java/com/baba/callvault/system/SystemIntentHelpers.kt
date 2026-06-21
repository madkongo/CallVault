/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.baba.callvault.BuildConfig
import com.baba.callvault.R
import com.baba.callvault.utils.AppLogger

/**
 * SystemIntentHelpers.kt contains shortcuts for opening system screens and doing
 * other [Context] related tasks.
 */

private const val TAG = "CV:SystemIntentHelpers"

/**
 * The upstream project this app is forked from. Surfaced in the About screen as a
 * required fork attribution under the upstream license's GPLv3 Section 7 terms.
 */
const val ORIGINAL_PROJECT_URL = "https://github.com/kitsumed/ShizuCallRecorder"

/**
 * A folder-picker that asks for long-term read and write access to the chosen folder.
 *
 * Android normally only grants temporary access to a folder. This contract also requests
 * "persistable" access so the app can still read and write the folder after a reboot -
 * without asking the user again.
 */
class PersistentFolderPickerContract : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        // The [input] (when non-null) is the slot's currently-saved tree URI, used as the picker's
        // EXTRA_INITIAL_URI so it opens at that folder. AOSP's documented-correct form for the initial
        // location is a DOCUMENT uri (or a tree uri WITH a document id), NOT a bare persisted tree uri
        // (content://…/tree/<id>) — AndroidX passes our input straight into EXTRA_INITIAL_URI unchanged,
        // so we convert tree -> document here. NOTE: EXTRA_INITIAL_URI is a best-effort HINT; OEM pickers
        // (e.g. OxygenOS) are permitted to ignore it and reopen at the last-browsed location.
        val initialUri = input?.let { tree ->
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            }.getOrNull()
        }
        return super.createIntent(context, initialUri).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // Makes the access survive app restarts and reboots.
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
    }
}

/**
 * Locks in long-term read and write access to [uri] so it remains valid after a reboot.
 * Call this immediately after the user picks a folder with [PersistentFolderPickerContract].
 *
 * @param uri The folder URI returned by [PersistentFolderPickerContract].
 */
fun Context.takePersistableFolderPermission(uri: Uri) {
    contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
}

/**
 * Opens the App Info page for this app.
 * The user can manually grant or revoke permissions from here.
 */
fun Context.openAppSettings() {
    launchSmartIntent(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
        }
    )
}

/**
 * Opens the system Developer Options screen, where "Wireless debugging" and
 * "Pair device with pairing code" live. Falls back to the top-level Settings screen if the
 * developer-settings activity isn't found on this device/ROM.
 */
fun Context.openDeveloperSettings() {
    val opened = runCatching {
        launchSmartIntent(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }.isSuccess
    if (!opened) {
        runCatching { launchSmartIntent(Intent(Settings.ACTION_SETTINGS)) }
    }
}

/**
 * Opens the device-info ("About phone") screen, where the user taps Build number 7 times to
 * unlock Developer Options. Falls back to the top-level Settings screen if unavailable.
 */
fun Context.openDeviceInfoSettings() {
    val opened = runCatching {
        launchSmartIntent(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }.isSuccess
    if (!opened) {
        runCatching { launchSmartIntent(Intent(Settings.ACTION_SETTINGS)) }
    }
}

/**
 * Opens the system Wireless-debugging screen as directly as the device allows, with layered
 * fallbacks (devices differ — e.g. OxygenOS has no direct activity):
 *   1) the direct AOSP/Pixel "Wireless debugging" activity,
 *   2) Developer Options scrolled to + highlighting the Wireless-debugging toggle,
 *   3) plain Developer Options, then 4) all Settings.
 * Each candidate is tried until one launches.
 */
fun Context.openWirelessDebugging() {
    val highlightKey = "toggle_adb_wireless"
    val candidates = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$AdbWirelessSettingsActivity"),
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", highlightKey)
            putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", highlightKey) })
        },
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        if (runCatching { launchSmartIntent(intent) }.isSuccess) return
    }
}

/** Opens the upstream project repository (required fork attribution, GPLv3 §7). */
fun Context.openOriginalProjectRepo() {
    launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = ORIGINAL_PROJECT_URL.toUri() })
}

/**
 * Copies [text] to the clipboard and shows a short confirmation message.
 * Safe to call from any thread.
 *
 * @param label A short name for the copied item (shown in clipboard managers).
 * @param text  The text to copy.
 */
fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(this, getString(R.string.general_copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Launches [intent] safely regardless of whether this [Context] is an [Activity] or not.
 *
 * When called from a non-Activity context (e.g. a ViewModel's applicationContext or a
 * background Service), Android requires [Intent.FLAG_ACTIVITY_NEW_TASK] to start a new
 * Activity.
 * @param intent The [Intent] to launch. The flag is added in-place only when needed.
 */
private fun Context.launchSmartIntent(intent: Intent) {
    if (this !is Activity) {
        if (BuildConfig.DEBUG) {
            AppLogger.w(
                TAG,
                "launchSmartIntent called from a non-Activity context (${this::class.simpleName}). " +
                "FLAG_ACTIVITY_NEW_TASK will be added automatically, but the user may not be able " +
                "to press Back to return to this app from the launched screen."
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
