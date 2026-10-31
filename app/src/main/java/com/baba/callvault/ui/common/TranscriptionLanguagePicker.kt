/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.baba.callvault.R
import com.baba.callvault.transcription.TranscriptionLanguageChoice

/** How many rows to leave above the selected one when the list opens scrolled to it. */
private const val ROWS_ABOVE_SELECTED = 2

/** Tall enough for most of the list, short enough that the dialog never reads as a new screen. */
private val DIALOG_MAX_HEIGHT = 560.dp

/**
 * The one transcription-language picker, used by Settings (classic and per-page), the setup wizard and
 * the "Which language?" dialog before a transcription or import.
 *
 * It looks like the dropdown field it replaced, and the same tap opens it — but onto a dialog with a
 * search box at the top, because a list of sixteen-plus languages is long enough that scrolling for
 * yours is the slow part, and someone whose app is not in their language should be able to type the
 * name the way they know it (see [LanguageSearch]).
 *
 * With the box empty the list is exactly the old one: auto-detect first, then A-Z by shown name.
 *
 * @param label the field's label, e.g. "Language".
 * @param selectedKey the encoded choice ([TranscriptionLanguageChoice.encode]), auto-detect included.
 * @param onSelected given the encoded key of the row picked.
 */
@Composable
fun TranscriptionLanguagePicker(
    label: String,
    selectedKey: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = LanguageSearch.itemsFor(
        TranscriptionLabels.sortLanguageOptions(
            TranscriptionLabels.LANGUAGE_OPTIONS.map { code ->
                TranscriptionLanguageChoice.encode(code) to stringResource(TranscriptionLabels.languageOf(code))
            }
        )
    )
    // Never null in practice: callers pass a key out of this same list, and an unknown one shows auto.
    val selected = items.firstOrNull { it.key == selectedKey } ?: items.first()
    var open by rememberSaveable { mutableStateOf(false) }

    LanguageField(label = label, value = selected.label, onClick = { open = true }, modifier = modifier)

    if (open) {
        LanguagePickerDialog(
            title = label,
            items = items,
            selectedKey = selected.key,
            onPick = { key ->
                open = false
                onSelected(key)
            },
            onDismiss = { open = false },
        )
    }
}

/**
 * The closed state: a read-only field that looks like the old dropdown. The whole field is one
 * target, and TalkBack hears it as a drop-down list named [label] showing [value].
 */
@Composable
private fun LanguageField(label: String, value: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            colors = pickerFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
        )
        // Over the field, so a tap anywhere on it opens the list instead of placing a cursor.
        Box(
            Modifier
                .matchParentSize()
                .clip(OutlinedTextFieldDefaults.shape)
                .clickable(role = Role.DropdownList, onClick = onClick)
                .semantics {
                    contentDescription = label
                    stateDescription = value
                }
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    title: String,
    items: List<LanguageSearchItem>,
    selectedKey: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query, items) { LanguageSearch.filter(items, query) }
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    // Opens scrolled to the current choice, so it is in view without hunting for it.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - ROWS_ABOVE_SELECTED).coerceAtLeast(0)
    )

    // Compose's Dialog window resizes for the keyboard, so the list shrinks above it rather than
    // sitting underneath it; the weight below is what lets the list be the part that gives way.
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.heightIn(max = DIALOG_MAX_HEIGHT),
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                LanguageSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.language_search_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .selectableGroup(),
                    ) {
                        items(shown, key = { it.key }) { item ->
                            LanguageRow(item.label, item.key == selectedKey) { onPick(item.key) }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
                }
            }
        }
    }
}

/**
 * The search box: magnifier at the start, × at the end once there is text. Start and end rather than
 * left and right, so Arabic and Persian get them mirrored by the layout itself.
 */
@Composable
private fun LanguageSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val primary = MaterialTheme.colorScheme.primary
    // Stated, so the drag handles do not fall back to a scheme colour this theme renders coral.
    val selectionColors = TextSelectionColors(handleColor = primary, backgroundColor = primary.copy(alpha = 0.4f))

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            shape = CircleShape,
            placeholder = { Text(stringResource(R.string.language_search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.language_search_clear))
                    }
                }
            },
            // "Search" on the keyboard puts it away, so the narrowed list is all in view.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = pickerFieldColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = modifier,
        )
    }
}

@Composable
private fun LanguageRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Outline, cursor and icons in the app's primary teal, stated rather than left to Material's defaults,
 * some of which this scheme resolves to coral — the colour the app keeps for trouble.
 */
@Composable
private fun pickerFieldColors(containerColor: Color = Color.Transparent): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outline,
        cursorColor = scheme.primary,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        focusedLeadingIconColor = scheme.primary,
        unfocusedLeadingIconColor = scheme.primary,
        focusedTrailingIconColor = scheme.primary,
        unfocusedTrailingIconColor = scheme.primary,
        focusedContainerColor = containerColor,
        unfocusedContainerColor = containerColor,
    )
}
