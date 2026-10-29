/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.transcription.cloud.CloudEndpoint
import com.baba.callvault.transcription.cloud.CloudKeyStore
import com.baba.callvault.transcription.cloud.CloudTranscriptionClient
import com.baba.callvault.transcription.cloud.TranscriptionService
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Where to transcribe" (issue #48, approved design A): on this phone, or a cloud service with the
 * user's own key. The chosen cloud row opens to its key, a Test button and where to get a key; under
 * the list sit the mobile-data switch and a note saying plainly where the audio goes.
 *
 * Choosing a cloud service for the first time asks first; Cancel keeps what was chosen before.
 * No prices, costs or "free" anywhere: we do not charge for this and do not speak for those who do.
 */
@Composable
internal fun TranscriptionServiceRows(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val service = remember(updateTrigger) { preferences.getTranscriptionService() }
    val mobileData = remember(updateTrigger) { preferences.getTranscriptionCloudMobileData() }
    var pendingConsent by remember { mutableStateOf<TranscriptionService?>(null) }

    SettingsSubHeader(stringResource(R.string.transcription_where_title))
    Column(Modifier.selectableGroup()) {
        TranscriptionService.entries.forEach { option ->
            val selected = option == service
            CloudServiceOption(option, selected) {
                when {
                    selected -> Unit
                    option.isCloud && !preferences.hasCloudConsent(option) -> pendingConsent = option
                    else -> actions.setTranscriptionService(option)
                }
            }
            if (selected && option.isCloud) CloudServiceDetails(option, preferences)
        }
    }

    if (service.isCloud) {
        SettingsToggleRow(
            icon = Icons.Filled.SignalCellularAlt,
            label = stringResource(R.string.transcription_cloud_mobile_data),
            description = stringResource(R.string.transcription_cloud_mobile_data_desc),
            checked = mobileData,
            onCheckedChange = { actions.setTranscriptionCloudMobileData(it) },
        )
        CloudPrivacyFootnote(serviceLabel(service))
    }

    pendingConsent?.let { asked ->
        CloudConsentDialog(
            serviceName = serviceLabel(asked),
            onConfirm = {
                preferences.recordCloudConsent(asked)
                actions.setTranscriptionService(asked)
                pendingConsent = null
            },
            onDismiss = { pendingConsent = null },
        )
    }
}

/** The name the user sees for [service]: the preset's, or "your server" for Custom. */
@Composable
private fun serviceLabel(service: TranscriptionService): String =
    service.preset?.displayName ?: stringResource(R.string.transcription_where_custom_service)

@Composable
private fun CloudServiceOption(option: TranscriptionService, selected: Boolean, onSelect: () -> Unit) {
    val title = when (option) {
        TranscriptionService.ON_DEVICE -> stringResource(R.string.transcription_where_on_device)
        TranscriptionService.CUSTOM -> stringResource(R.string.transcription_where_custom)
        else -> option.preset?.displayName.orEmpty()
    }
    val description = when (option) {
        TranscriptionService.ON_DEVICE -> stringResource(R.string.transcription_where_on_device_desc)
        TranscriptionService.GROQ -> stringResource(R.string.transcription_where_groq_desc)
        TranscriptionService.OPENROUTER -> stringResource(R.string.transcription_where_openrouter_desc)
        TranscriptionService.CUSTOM -> stringResource(R.string.transcription_where_custom_desc)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Display only; the row owns the tap and the semantics. Colours stated: see the M3-red rule.
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (option == TranscriptionService.RECOMMENDED) RecommendedChip()
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecommendedChip() {
    Text(
        text = stringResource(R.string.transcription_where_recommended).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** What the Test button last said, or null before it is pressed. */
private enum class TestState { RUNNING, WORKS, BAD_KEY, NO_TIMINGS, UNREACHABLE, REFUSED, NO_KEY, INCOMPLETE }

/**
 * The open cloud row: (Custom: address and model,) the key, Test, and where to get a key.
 *
 * The key is saved as it is typed — a moment after the last keystroke, off the main thread, sealed by
 * the Keystore — so there is no Save button to forget. It is never logged.
 */
@Composable
private fun CloudServiceDetails(service: TranscriptionService, preferences: AppPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyStore = remember(service) { CloudKeyStore(context) }
    var key by remember(service) { mutableStateOf<String?>(null) }
    var savedKey by remember(service) { mutableStateOf<String?>(null) }
    var keyVisible by remember(service) { mutableStateOf(false) }
    var baseUrl by remember(service) { mutableStateOf(preferences.getTranscriptionCustomBaseUrl()) }
    var model by remember(service) { mutableStateOf(preferences.getTranscriptionCustomModel()) }
    var test by remember(service) { mutableStateOf<TestState?>(null) }
    val label = serviceLabel(service)

    // Read once, off the main thread: decrypting goes through the Keystore.
    LaunchedEffect(service) {
        val stored = withContext(Dispatchers.IO) { keyStore.get(service).orEmpty() }
        savedKey = stored
        key = stored
    }
    LaunchedEffect(service, key) {
        val typed = key ?: return@LaunchedEffect
        if (typed == savedKey) return@LaunchedEffect
        delay(KEY_SAVE_DELAY_MS)
        withContext(Dispatchers.IO) {
            runCatching { keyStore.set(service, typed) }
                .onSuccess { savedKey = typed }
                .onFailure { AppLogger.w(TAG, "Could not save the key (${it.javaClass.simpleName})") }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
    ) {
        if (service == TranscriptionService.CUSTOM) {
            val urlInvalid = baseUrl.isNotBlank() && CloudEndpoint.validCustomBaseUrl(baseUrl) == null
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    preferences.setTranscriptionCustomBaseUrl(it)
                    test = null
                },
                label = { Text(stringResource(R.string.transcription_cloud_custom_url)) },
                placeholder = { Text(stringResource(R.string.transcription_cloud_custom_url_hint)) },
                isError = urlInvalid,
                supportingText = if (urlInvalid) {
                    { Text(stringResource(R.string.transcription_cloud_custom_url_invalid)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    preferences.setTranscriptionCustomModel(it)
                    test = null
                },
                label = { Text(stringResource(R.string.transcription_cloud_custom_model)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = key.orEmpty(),
            onValueChange = {
                key = it
                test = null
            },
            label = { Text(stringResource(R.string.transcription_cloud_key_label, label)) },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (keyVisible) R.string.transcription_cloud_key_hide else R.string.transcription_cloud_key_show
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = test != TestState.RUNNING,
                onClick = {
                    val typed = key.orEmpty()
                    val endpoint = CloudEndpoint.resolve(service, baseUrl, model)
                    test = when {
                        endpoint == null -> TestState.INCOMPLETE
                        typed.isBlank() -> TestState.NO_KEY
                        else -> TestState.RUNNING
                    }
                    if (test != TestState.RUNNING || endpoint == null) return@Button
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { keyStore.set(service, typed) }
                            // Never a crash from a button: anything unexpected reads as "refused".
                            runCatching { CloudTranscriptionClient().test(endpoint, typed) }
                                .onFailure { AppLogger.w(TAG, "Key test failed (${it.javaClass.simpleName})") }
                                .getOrDefault(CloudTranscriptionClient.TestOutcome.REFUSED)
                        }
                        test = outcome.toTestState()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    stringResource(
                        if (test == TestState.RUNNING) R.string.transcription_cloud_testing else R.string.transcription_cloud_test
                    )
                )
            }
            Spacer(Modifier.width(12.dp))
            test?.takeIf { it != TestState.RUNNING }?.let { TestResultText(it, Modifier.weight(1f)) }
        }

        service.preset?.let { preset ->
            val uriHandler = LocalUriHandler.current
            TextButton(
                onClick = { runCatching { uriHandler.openUri(preset.keyPageUrl) } },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(stringResource(R.string.transcription_cloud_get_key, preset.displayName))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun CloudTranscriptionClient.TestOutcome.toTestState(): TestState = when (this) {
    CloudTranscriptionClient.TestOutcome.WORKS -> TestState.WORKS
    CloudTranscriptionClient.TestOutcome.BAD_KEY -> TestState.BAD_KEY
    CloudTranscriptionClient.TestOutcome.NO_TIMINGS -> TestState.NO_TIMINGS
    CloudTranscriptionClient.TestOutcome.UNREACHABLE -> TestState.UNREACHABLE
    CloudTranscriptionClient.TestOutcome.REFUSED -> TestState.REFUSED
}

@Composable
private fun TestResultText(state: TestState, modifier: Modifier) {
    val text = when (state) {
        TestState.WORKS -> R.string.transcription_cloud_test_ok
        TestState.BAD_KEY -> R.string.transcription_cloud_test_bad_key
        TestState.NO_TIMINGS -> R.string.transcription_cloud_test_no_timings
        TestState.UNREACHABLE -> R.string.transcription_cloud_test_unreachable
        TestState.REFUSED -> R.string.transcription_cloud_test_refused
        TestState.NO_KEY -> R.string.transcription_cloud_test_no_key
        TestState.INCOMPLETE, TestState.RUNNING -> R.string.transcription_cloud_test_incomplete
    }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = if (state == TestState.WORKS) LocalCvBrand.current.success else MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary,
)

/** Says, every time a cloud service is on, where the audio goes. Always shown: it is the consequence. */
@Composable
private fun CloudPrivacyFootnote(serviceName: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).padding(top = 1.dp),
        )
        Text(
            text = stringResource(R.string.transcription_cloud_privacy_note, serviceName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Asked once per service, the first time it is chosen. Cancel changes nothing. */
@Composable
private fun CloudConsentDialog(serviceName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.transcription_cloud_consent_title, serviceName)) },
        text = { Text(stringResource(R.string.transcription_cloud_consent_body, serviceName)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text(stringResource(R.string.transcription_cloud_consent_confirm, serviceName)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text(stringResource(R.string.general_cancel)) }
        },
    )
}

private const val TAG = "CV:CloudSettings"

/** How long after the last keystroke the key is sealed and saved. */
private const val KEY_SAVE_DELAY_MS = 400L
