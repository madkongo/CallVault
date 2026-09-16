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
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.baba.callvault.BuildConfig
import com.baba.callvault.R
import com.baba.callvault.utils.AppLogger
import java.io.File

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
 * The maintainer's Ko-fi page. Surfaced as an optional "support development" link on Home and in
 * About. Donations happen entirely on Ko-fi's site (opened in the browser) — no in-app payment SDK.
 */
const val KOFI_SUPPORT_URL = "https://ko-fi.com/madkongo"

/**
 * The project's Telegram group. Offered in the app because most questions are never asked at all when
 * the only way to ask is to open a GitHub issue.
 */
const val TELEGRAM_GROUP_URL = "https://t.me/+bAnxwAywhdk4MzM8"

/**
 * The maintainer's PayPal page, offered next to [KOFI_SUPPORT_URL] because the two are not
 * interchangeable for everyone: Ko-fi's card processing is unavailable or awkward in some countries,
 * and plenty of people simply already have a PayPal balance. Same arrangement — the browser opens
 * their site, no payment SDK ships in the app.
 */
const val PAYPAL_SUPPORT_URL = "https://paypal.me/MadKongo"

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

/** Opens the project's Telegram group, in the Telegram app when it is installed, else the browser. */
fun Context.openTelegramGroup() {
    launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = TELEGRAM_GROUP_URL.toUri() })
}

/** Opens the maintainer's Ko-fi page in the browser so the user can support development. */
fun Context.openKofi() {
    launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = KOFI_SUPPORT_URL.toUri() })
}

/** Opens the maintainer's PayPal page in the browser. The alternative to [openKofi], not a successor. */
fun Context.openPayPal() {
    launchSmartIntent(Intent(Intent.ACTION_VIEW).apply { data = PAYPAL_SUPPORT_URL.toUri() })
}

/**
 * Offers an exported transcript to the share-sheet.
 *
 * Separate from [shareLogFiles] because of [mimeType]: the log report is always plain text, while an
 * export can be any of five formats, and the type is what decides which apps the chooser offers. A
 * `.srt` announced as `text/plain` is offered to messaging apps and not to the subtitle editor the
 * user exported it for.
 *
 * The file is exposed through [FileProvider] and the chosen app is granted temporary read access for
 * the lifetime of the share — the same terms as the log report, and the reason the export is written
 * to its own cache sub-folder rather than anywhere else.
 */
fun Context.shareTranscriptFile(file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The flag alone covers only what the extras name, so the URI has to be in the clip data too.
        clipData = android.content.ClipData.newRawUri(file.name, uri)
    }
    launchSmartIntent(Intent.createChooser(intent, getString(R.string.transcript_export_chooser)))
}

/**
 * Shares [file] (the diagnostic log report) through the system share-sheet (ACTION_SEND), so the
 * user can attach it to an email, chat, or GitHub issue in one tap.
 *
 * The file is exposed via [FileProvider] (authority `${applicationId}.fileprovider`) and the receiving
 * app is granted temporary read access for the lifetime of the share.
 *
 * @param file The report file produced by `AppLogger.buildShareableReport`.
 */
fun Context.shareLogFile(file: File) = shareLogFiles(listOf(file))

/**
 * Shares the diagnostic report and, when one was collected, the system-log slice beside it.
 *
 * Two files rather than one concatenated: they come from different places and answer different
 * questions — the app's own log is what CallVault believes happened, and the logcat slice is what the
 * daemon and the platform actually reported. Keeping them apart means a reader can tell which is
 * which, and a failure to collect the second one costs nothing but the second one.
 */
fun Context.shareLogFiles(files: List<File>) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
    val subject = "CallVault debug logs"

    val sendIntent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }
    sendIntent.apply {
        putExtra(Intent.EXTRA_SUBJECT, subject)
        // Grant the chosen app temporary read access to every attached URI. The flag alone covers
        // only what the clip data and extras name, so each URI has to be in the clip data too.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(subject, uris.first()).apply {
            uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
        }
    }
    launchSmartIntent(Intent.createChooser(sendIntent, getString(R.string.settings_bugreport_share_chooser)))
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
 * Shares plain text through the system share sheet.
 *
 * Text rather than a file on purpose, for sharing a transcript: no temporary file is written, so
 * nothing is left behind on disk for a share the user may cancel, and no FileProvider grant is needed.
 *
 * @param subject Offered to apps that show one (mail, notes). Ignored by most.
 * @param text    The body to share.
 */
fun Context.sharePlainText(subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    launchSmartIntent(Intent.createChooser(intent, null))
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
