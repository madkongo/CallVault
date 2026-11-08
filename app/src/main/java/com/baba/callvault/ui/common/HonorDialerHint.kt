/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.HONOR_DIALER_FOLDER
import com.baba.callvault.utils.HonorDialerSetup
import com.baba.callvault.utils.HonorDialerSetup.Missing

/**
 * Shown under the file-name preset while "Honor / Huawei phone app" is picked (#56). The name alone is not
 * enough: the phone app only reads [HONOR_DIALER_FOLDER], and only `.m4a`. Says where the file must go,
 * names the CallVault settings that put it there (by their on-screen titles, so it cannot drift from them),
 * and lists which of those are still wrong.
 *
 * @param refreshKey Re-reads the settings when it changes. Pass whatever changes when the folder, codec or
 *   storage target does (the wizard's update trigger, for one).
 */
@Composable
fun HonorDialerHint(refreshKey: Any? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val missing = remember(refreshKey) {
        val preferences = AppPreferences(context)
        val treeId = preferences.getRecordingFolderUri()?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        HonorDialerSetup.missing(treeId, preferences.getAudioCodec(), preferences.getStorageTarget())
    }

    val folderSetting = settingPath(R.string.settings_group_save_and_sync, R.string.settings_recording_folder_label)
    val codecSetting = settingPath(R.string.settings_group_sound_and_names, R.string.settings_audio_codec)
    val targetSetting = settingPath(R.string.settings_group_save_and_sync, R.string.settings_storage_target_label)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.honor_dialer_hint, HONOR_DIALER_FOLDER, folderSetting, codecSetting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (missing.isEmpty()) {
            Text(
                text = stringResource(R.string.honor_dialer_ready),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            val names = missing.map {
                when (it) {
                    Missing.FOLDER -> folderSetting
                    Missing.CODEC -> codecSetting
                    Missing.STORAGE_TARGET -> targetSetting
                }
            }
            Text(
                text = stringResource(R.string.honor_dialer_missing, names.joinToString("; ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** "Section ▸ Setting", from the titles the Settings screen itself shows. */
@Composable
private fun settingPath(sectionRes: Int, rowRes: Int): String =
    stringResource(sectionRes) + " ▸ " + stringResource(rowRes)
