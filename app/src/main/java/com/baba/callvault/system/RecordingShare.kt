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
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.baba.callvault.R
import com.baba.callvault.utils.AppLogger

private const val TAG = "CV:Share"

/** Used when the provider cannot say what the file is — every recording is audio regardless. */
private const val FALLBACK_MIME = "audio/*"

/**
 * Shares a clip ("Share a part") with, optionally, what was said in it as the message text.
 *
 * The clip is a temporary file in the cache's `clips/` folder, exposed through the FileProvider for the share only.
 */
fun Context.shareClip(file: java.io.File, mime: String, text: String?) {
    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The flag alone covers only what the extras name, so the URI has to be in the clip data too.
        clipData = ClipData.newRawUri(file.name, uri)
    }
    val chooser = Intent.createChooser(intent, getString(R.string.share_clip_chooser)).apply {
        if (this@shareClip !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Length only, never the words: this log can end up in a report.
    AppLogger.i(TAG, "Sharing a clip (${file.length()} bytes) with ${text?.length ?: 0} characters of transcript text")
    runCatching { startActivity(chooser) }
        .onFailure { AppLogger.w(TAG, "Could not open the share sheet for the clip '${file.name}': ${it.message}") }
}

/**
 * Sends a call's files together — its recording and the transcript and summary written beside it — in one share.
 *
 * The recording's URI belongs to the documents provider and the text files to our FileProvider; both can extend a
 * read grant, and every URI goes into the [ClipData] so the grant reaches all of them. The type is the files' own
 * when they agree (several recordings), audio when they are all audio, and any type otherwise.
 */
fun Context.shareCallFiles(uris: List<Uri>, subject: String) {
    if (uris.isEmpty()) return
    val types = uris.mapNotNull { runCatching { contentResolver.getType(it) }.getOrNull() }.distinct()
    val mime = types.singleOrNull()
        ?: if (types.isNotEmpty() && types.all { it.startsWith("audio/") }) FALLBACK_MIME else "*/*"
    val clip = ClipData.newRawUri(subject, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.single()) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
    }.apply {
        type = mime
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = clip
    }
    val chooser = Intent.createChooser(intent, getString(R.string.home_share_chooser)).apply {
        if (this@shareCallFiles !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Counts only: names can be contacts, and this log can end up in a report.
    AppLogger.i(TAG, "Sharing ${uris.size} file(s) of type $mime")
    runCatching { startActivity(chooser) }
        .onFailure { AppLogger.w(TAG, "Could not open the share sheet for ${uris.size} file(s): ${it.message}") }
}

