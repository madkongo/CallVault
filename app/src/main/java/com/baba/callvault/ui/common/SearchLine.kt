/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.PopupProperties
import com.baba.callvault.R

/**
 * The search line at the head of a list page: a magnifier inside the field, a × once there is
 * something to clear, and — when [suggestions] are given — a menu of completions under it.
 *
 * One composable for the three pages so they read as the same control. It holds no state of its
 * own beyond whether the menu was waved away: what is typed belongs to the page, which is what
 * filters on it, and the page decides what (if anything) is offered to complete it.
 *
 * A plain [DropdownMenu] rather than Material's `ExposedDropdownMenuBox`: that box opens and closes
 * on its own rules about taps and focus, and on the OP9 a Hebrew letter typed into the field showed
 * no menu although a match existed. Here the menu is open exactly when there is something in it and
 * it has not been dismissed, and it takes no focus, so the keyboard stays up while it is shown.
 *
 * @param suggestions completions for what is typed; empty hides the menu. A page with nothing to
 *   complete (a word search) passes none and gets a plain field.
 * @param onSuggestionPicked called with the completion chosen; the page usually sets the query to it.
 */
@Composable
fun SearchLine(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    suggestions: List<String> = emptyList(),
    onSuggestionPicked: (String) -> Unit = onQueryChange,
) {
    // Waving the menu away holds until the query changes; the next keystroke is a new question.
    var dismissed by remember(query) { mutableStateOf(false) }
    var fieldWidthPx by remember { mutableIntStateOf(0) }
    val fieldWidth = with(LocalDensity.current) { fieldWidthPx.toDp() }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { fieldWidthPx = it.width },
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
        DropdownMenu(
            expanded = suggestions.isNotEmpty() && !dismissed,
            onDismissRequest = { dismissed = true },
            // Not focusable: taking focus would drop the keyboard mid-word.
            properties = PopupProperties(focusable = false),
            modifier = Modifier.width(fieldWidth),
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
