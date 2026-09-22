/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.baba.callvault.R

/**
 * The search line at the head of a list page: a magnifier inside the field, a × once there is
 * something to clear, and — when [suggestions] are given — a menu of completions under it.
 *
 * One composable for the three pages so they read as the same control. It holds no state of its
 * own beyond whether the menu is open: what is typed belongs to the page, which is what filters on
 * it, and the page decides what (if anything) is offered to complete it.
 *
 * @param suggestions completions for what is typed; empty hides the menu. A page with nothing to
 *   complete (a word search) passes none and gets a plain field.
 * @param onSuggestionPicked called with the completion chosen; the page usually sets the query to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchLine(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    suggestions: List<String> = emptyList(),
    onSuggestionPicked: (String) -> Unit = onQueryChange,
) {
    // Open only while there is something to show; the box's own toggle would open an empty menu
    // on every tap into the field.
    var dismissed by remember(query) { mutableStateOf(false) }
    val expanded = suggestions.isNotEmpty() && !dismissed

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (!it) dismissed = true },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            shape = CircleShape,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { dismissed = true },
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        dismissed = true
                        onSuggestionPicked(suggestion)
                    },
                )
            }
        }
    }
}
