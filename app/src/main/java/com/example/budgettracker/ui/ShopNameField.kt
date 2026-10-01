package com.example.budgettracker.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.budgettracker.data.ShopNameMatcher
import com.example.budgettracker.ui.icons.Store
import com.example.budgettracker.ui.theme.CardDark
import com.example.budgettracker.ui.theme.Positive

/**
 * Shop name text field with a dropdown of matching saved shops (autocomplete).
 *
 * Typing filters the list (prefix, substring and fuzzy matches, so "LGDL" still
 * suggests "LIDL"). A name that isn't saved yet can be kept via the
 * "Add … as new shop" entry; the shop itself is created when the expense is saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopNameField(
    value: String,
    onValueChange: (String) -> Unit,
    shopNames: List<String>,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    suggestionIcon: ImageVector = Icons.Filled.Store,
    newEntryLabel: (String) -> String = { "Add \"$it\" as new shop" }
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions = remember(value, shopNames) { ShopNameMatcher.suggestions(value, shopNames) }
    val trimmed = value.trim()
    val isNewName = trimmed.isNotEmpty() && shopNames.none { it.equals(trimmed, ignoreCase = true) }
    val showMenu = expanded && (suggestions.isNotEmpty() || isNewName)

    ExposedDropdownMenuBox(
        expanded = showMenu,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = label,
            placeholder = placeholder,
            singleLine = true,
            trailingIcon = trailingIcon ?: { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showMenu) },
            colors = colors,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { expanded = false },
            containerColor = CardDark
        ) {
            suggestions.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, color = Color.White) },
                    leadingIcon = {
                        Icon(suggestionIcon, null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        onValueChange(name)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
            if (isNewName) {
                if (suggestions.isNotEmpty()) HorizontalDivider(color = Color(0xFF2A3040))
                DropdownMenuItem(
                    text = { Text(newEntryLabel(trimmed), color = Positive, fontWeight = FontWeight.Medium) },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, null, tint = Positive, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        onValueChange(trimmed)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
