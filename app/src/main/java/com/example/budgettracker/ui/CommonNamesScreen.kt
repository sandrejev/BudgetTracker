package com.example.budgettracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.data.AliasRow
import com.example.budgettracker.data.CommonNameRow
import com.example.budgettracker.data.ItemCategory
import com.example.budgettracker.ui.theme.BackgroundDark
import com.example.budgettracker.ui.theme.CardDark
import com.example.budgettracker.ui.theme.Negative
import com.example.budgettracker.ui.theme.Positive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── List ─────────────────────────────────────────────────────────────────────

/**
 * Settings → Common item names: searchable list (matches common names and receipt
 * texts). Tap to edit; long-press to select several, then merge or delete them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonNamesScreen(
    viewModel: BudgetViewModel,
    onBack: () -> Unit,
    onOpen: (commonNameId: Long) -> Unit
) {
    val query by viewModel.commonNameQuery.collectAsState()
    val rows by viewModel.commonNameResults.collectAsState()
    val categories by viewModel.categories.collectAsState()
    // Kept by id so a selection survives changing the search text
    var selected by remember { mutableStateOf(mapOf<Long, CommonNameRow>()) }
    var showMerge by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    fun toggle(row: CommonNameRow) {
        selected = if (row.id in selected) selected - row.id else selected + (row.id to row)
    }

    BackHandler(enabled = selecting) { selected = emptyMap() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) "${selected.size} selected" else "Common item names",
                        color = Color.White, fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) selected = emptyMap() else onBack() }) {
                        if (selecting) Icon(Icons.Filled.Close, "Clear selection", tint = Color.White)
                        else Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { showMerge = true }, enabled = selected.size >= 2) {
                            Text("Merge", color = if (selected.size >= 2) Positive else Color(0xFF555555))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = Color(0xFFAAAAAA))
                        }
                    } else {
                        IconButton(onClick = { showAdd = true }) {
                            Icon(Icons.Filled.Add, "Add common name", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { pv ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pv)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.commonNameQuery.value = it },
                placeholder = { Text("Search names and receipt texts", color = Color(0xFF555555)) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color(0xFF888888)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.commonNameQuery.value = "" }) {
                            Icon(Icons.Filled.Close, "Clear", tint = Color(0xFF888888))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = itemFieldColors()
            )
            Text(
                "Tap to edit · long-press to select several and merge them",
                fontSize = 12.sp, color = Color(0xFF555555),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (rows.isEmpty()) {
                Text(
                    if (query.isBlank()) "No common names yet. Use ✨ on a receipt to create them."
                    else "Nothing matches \"$query\"",
                    color = Color(0xFF666666), fontSize = 14.sp,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { row ->
                    val isSelected = row.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onLongClick = { toggle(row) },
                                onClick = { if (selecting) toggle(row) else onOpen(row.id) }
                            )
                            .background(if (isSelected) Positive.copy(alpha = 0.12f) else Color.Transparent)
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selecting) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { toggle(row) },
                                colors = CheckboxDefaults.colors(checkedColor = Positive)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(row.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                row.categoryName ?: "No category",
                                color = if (row.categoryName != null) Color(0xFF6A9B6A) else Color(0xFF666666),
                                fontSize = 12.sp
                            )
                            row.aliasNames?.let {
                                Text(it, color = Color(0xFF777777), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            "${row.aliasCount}",
                            color = Color(0xFF666666), fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    HorizontalDivider(color = Color(0xFF1E2229))
                }
            }
        }
    }

    if (showAdd) {
        AddCommonNameDialog(
            categories = categories,
            onDismiss = { showAdd = false },
            onCreate = { name, categoryId ->
                viewModel.createCommonName(name, categoryId).map { id -> showAdd = false; onOpen(id) }
            }
        )
    }

    if (showMerge && selected.size >= 2) {
        MergeDialog(
            names = selected.values.sortedBy { it.name.lowercase() },
            categories = categories,
            onDismiss = { showMerge = false },
            onMerge = { keepId, name, categoryId ->
                viewModel.mergeCommonNames(selected.keys.toList(), keepId, name, categoryId).map {
                    showMerge = false
                    selected = emptyMap()
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = CardDark,
            title = { Text("Delete ${selected.size} common name(s)?", color = Color.White) },
            text = {
                Text(
                    "Receipt items keep their text but lose this common name and category.",
                    color = Color(0xFFCCCCCC), fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCommonNames(selected.keys.toList())
                    selected = emptyMap()
                    confirmDelete = false
                }) { Text("Delete", color = Negative) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Color(0xFF888888)) }
            }
        )
    }
}

@Composable
private fun AddCommonNameDialog(
    categories: List<ItemCategory>,
    onDismiss: () -> Unit,
    onCreate: suspend (name: String, categoryId: Long?) -> Result<Unit>
) {
    var name by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("New common name", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = itemFieldColors()
                )
                CategoryDropdown(categories, categoryId, { categoryId = it }, Modifier.fillMaxWidth())
                error?.let { Text(it, color = Negative, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { onCreate(name, categoryId).onFailure { error = it.message } }
            }) { Text("Create", color = Positive) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF888888)) } }
    )
}

/** Pick which name to keep (it can be renamed) and the category; all receipt texts move to it. */
@Composable
private fun MergeDialog(
    names: List<CommonNameRow>,
    categories: List<ItemCategory>,
    onDismiss: () -> Unit,
    onMerge: suspend (keepId: Long, name: String, categoryId: Long?) -> Result<Unit>
) {
    var keepId by remember { mutableStateOf(names.first().id) }
    var name by remember { mutableStateOf(names.first().name) }
    var categoryId by remember {
        mutableStateOf(names.first().categoryId ?: names.firstNotNullOfOrNull { it.categoryId })
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Merge ${names.size} common names", color = Color.White) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Keep this name:", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                names.forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = row.id == keepId, onClick = {
                                keepId = row.id
                                name = row.name
                                row.categoryId?.let { categoryId = it }
                                error = null
                            }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = row.id == keepId, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Positive)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(row.name, color = Color.White, fontSize = 14.sp)
                            Text("${row.aliasCount} receipt text(s)", color = Color(0xFF777777), fontSize = 11.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text("Name after merging") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = itemFieldColors()
                )
                CategoryDropdown(categories, categoryId, { categoryId = it }, Modifier.fillMaxWidth())
                Text(
                    "All receipt texts of the selected names will convert to this one.",
                    color = Color(0xFF777777), fontSize = 12.sp
                )
                error?.let { Text(it, color = Negative, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { onMerge(keepId, name, categoryId).onFailure { error = it.message } }
            }) { Text("Merge", color = Positive) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF888888)) } }
    )
}

// ── Detail ───────────────────────────────────────────────────────────────────

/** Edit one common name: its name, category and the receipt texts that convert to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonNameDetailScreen(
    viewModel: BudgetViewModel,
    commonNameId: Long,
    onBack: () -> Unit
) {
    val commonName by remember(commonNameId) { viewModel.commonName(commonNameId) }.collectAsState(initial = null)
    val aliases by remember(commonNameId) { viewModel.aliasesFor(commonNameId) }.collectAsState(initial = emptyList())
    val categories by viewModel.categories.collectAsState()
    val cn = commonName
    // Re-initialised once the common name has loaded
    var nameText by remember(cn?.id) { mutableStateOf(cn?.name ?: "") }
    var categoryId by remember(cn?.id) { mutableStateOf(cn?.categoryId) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAddAlias by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val changed = cn != null && (nameText.trim() != cn.name || categoryId != cn.categoryId)

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Edit common name", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }, enabled = cn != null) {
                        Icon(Icons.Filled.Delete, "Delete", tint = Color(0xFFAAAAAA))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { pv ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pv)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OutlinedTextField(
                    value = nameText, onValueChange = { nameText = it; message = null },
                    label = { Text("Common name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = itemFieldColors()
                )
            }
            item {
                CategoryDropdown(categories, categoryId, { categoryId = it; message = null }, Modifier.fillMaxWidth())
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val current = cn ?: return@Button
                            scope.launch {
                                message = viewModel.updateCommonName(current, nameText, categoryId)
                                    .fold({ "Saved ✓" }, { it.message })
                            }
                        },
                        enabled = changed,
                        colors = ButtonDefaults.buttonColors(containerColor = Positive)
                    ) { Text("Save", color = Color(0xFF0E1116), fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    message?.let {
                        Text(it, color = if (it.endsWith("✓")) Positive else Negative, fontSize = 13.sp)
                    }
                }
            }
            item {
                HorizontalDivider(color = Color(0xFF222830), modifier = Modifier.padding(vertical = 8.dp))
                Text("Receipt texts converted to this name", fontSize = 13.sp, color = Color(0xFF888888))
                Text(
                    "Changes apply to all receipts with these texts.",
                    fontSize = 12.sp, color = Color(0xFF555555)
                )
            }
            items(aliases, key = { it.id }) { alias ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(alias.rawName, color = Color.White, fontSize = 14.sp)
                        Text(
                            "on ${alias.itemCount} receipt line(s)",
                            color = Color(0xFF666666), fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { viewModel.unlinkAlias(alias.id) }) {
                        Icon(Icons.Filled.Close, "Remove from this name", tint = Color(0xFF777777))
                    }
                }
                HorizontalDivider(color = Color(0xFF1E2229))
            }
            item {
                TextButton(onClick = { showAddAlias = true }) {
                    Icon(Icons.Filled.Add, null, tint = Positive, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add receipt text", color = Positive)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAddAlias) {
        AddAliasDialog(
            viewModel = viewModel,
            commonNameId = commonNameId,
            onDismiss = { showAddAlias = false }
        )
    }

    if (confirmDelete && cn != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = CardDark,
            title = { Text("Delete \"${cn.name}\"?", color = Color.White) },
            text = {
                Text(
                    "Its ${aliases.size} receipt text(s) will no longer have a common name.",
                    color = Color(0xFFCCCCCC), fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCommonNames(listOf(cn.id))
                    confirmDelete = false
                    onBack()
                }) { Text("Delete", color = Negative) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Color(0xFF888888)) }
            }
        )
    }
}

/** Search receipt texts and move one to this common name, or add a new text. */
@Composable
private fun AddAliasDialog(
    viewModel: BudgetViewModel,
    commonNameId: Long,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AliasRow>>(emptyList()) }
    LaunchedEffect(query) {
        delay(200)
        results = if (query.isBlank()) emptyList()
        else viewModel.searchAliases(query).filter { it.commonNameId != commonNameId }
    }
    val typed = query.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Add receipt text", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search receipt texts", color = Color(0xFF555555)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color(0xFF888888)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), colors = itemFieldColors()
                )
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(results, key = { it.id }) { alias ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.linkAlias(alias.rawName, commonNameId)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(alias.rawName, color = Color.White, fontSize = 14.sp)
                            Text(
                                alias.commonName?.let { "now: $it" } ?: "no common name yet",
                                color = Color(0xFF777777), fontSize = 11.sp
                            )
                        }
                        HorizontalDivider(color = Color(0xFF2A3040))
                    }
                }
                if (typed.isNotEmpty() && results.none { it.rawName == typed }) {
                    TextButton(onClick = {
                        viewModel.linkAlias(typed, commonNameId)
                        onDismiss()
                    }) { Text("Add \"$typed\" as a new receipt text", color = Positive) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF888888)) } }
    )
}

// ── Shared ───────────────────────────────────────────────────────────────────

/** Read-only dropdown to pick a category (or none). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDropdown(
    categories: List<ItemCategory>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories.firstOrNull { it.id == selectedId }?.name ?: "No category"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = itemFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = CardDark
        ) {
            DropdownMenuItem(
                text = { Text("No category", color = Color(0xFF888888)) },
                onClick = { onSelect(null); expanded = false }
            )
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name, color = Color.White) },
                    onClick = { onSelect(category.id); expanded = false }
                )
            }
        }
    }
}

@Composable
fun itemFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Positive,
    unfocusedBorderColor = Color(0xFF444444),
    focusedLabelColor = Positive,
    unfocusedLabelColor = Color(0xFF888888),
    cursorColor = Positive
)
