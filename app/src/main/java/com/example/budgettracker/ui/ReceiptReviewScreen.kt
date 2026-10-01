package com.example.budgettracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.receipt.Level0Doc
import com.example.budgettracker.receipt.ParsedReceipt
import com.example.budgettracker.receipt.parsePrice
import com.example.budgettracker.ui.theme.*
import kotlinx.coroutines.launch

/** Mutable row for editing in the review table. */
private data class ItemRow(
    val id: Int,
    var name: String,
    var priceText: String,
    var commonName: String? = null
) {
    val price: Double? get() = parsePrice(priceText) ?: priceText.replace(',', '.').toDoubleOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptReviewScreen(
    doc: Level0Doc,
    parsed: ParsedReceipt,
    processorName: String,
    detectedShop: String?,
    viewModel: BudgetViewModel,
    onBack: () -> Unit,
    onConfirmed: () -> Unit
) {
    var shopName by remember { mutableStateOf(detectedShop ?: "") }
    var rows by remember {
        mutableStateOf(
            parsed.items.mapIndexed { i, item ->
                ItemRow(i, item.name, "%.2f".format(item.price).replace('.', ','))
            }.toMutableList()
        )
    }
    var editingRowId by remember { mutableStateOf<Int?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var resolvingNames by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val itemsTotal = rows.sumOf { it.price ?: 0.0 }
    val detectedTotal = parsed.detectedTotal

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Review Receipt", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    // ✨ Resolve common names via LLM
                    IconButton(
                        onClick = {
                            if (!resolvingNames && rows.isNotEmpty()) {
                                resolvingNames = true
                                scope.launch {
                                    val resolved = viewModel.resolveNamesForReview(rows.map { it.name })
                                    rows = rows.toMutableList().also { list ->
                                        resolved.forEach { (idx, commonName) ->
                                            val i = idx - 1
                                            if (i in list.indices) list[i] = list[i].copy(commonName = commonName)
                                        }
                                    }
                                    resolvingNames = false
                                }
                            }
                        },
                        enabled = !resolvingNames && rows.isNotEmpty()
                    ) {
                        if (resolvingNames) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Positive
                            )
                        } else {
                            Icon(Icons.Filled.AutoAwesome, "Resolve names", tint = Color(0xFF888888))
                        }
                    }
                    IconButton(onClick = { viewModel.copyLevel0Json(doc) }) {
                        Icon(Icons.Filled.DataObject, "Copy Level 0 JSON", tint = Color.White)
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, "Add item", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        bottomBar = {
            Surface(color = CardDark) {
                Column(Modifier.padding(16.dp)) {
                    confirmError?.let {
                        Text(it, color = Negative, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Total: €%.2f".format(itemsTotal),
                                color = if (detectedTotal != null && Math.abs(itemsTotal - detectedTotal) > 0.02)
                                    Negative else Positive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            if (detectedTotal != null && Math.abs(itemsTotal - detectedTotal) > 0.02) {
                                Text(
                                    "Receipt total: €%.2f".format(detectedTotal),
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val items = rows.map { row ->
                                    val p = row.price
                                    if (p == null) {
                                        confirmError = "Invalid price for \"${row.name}\""
                                        return@Button
                                    }
                                    Triple(row.name, p, row.commonName)
                                }
                                if (items.isEmpty()) {
                                    confirmError = "Add at least one item"
                                    return@Button
                                }
                                val total = detectedTotal ?: itemsTotal
                                viewModel.confirmReceiptImport(
                                    shopName = shopName.trim().ifBlank { null },
                                    amount = total,
                                    items = items,
                                    doc = doc,
                                    parsedReceipt = parsed
                                )
                                onConfirmed()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Positive)
                        ) {
                            Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { pv ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pv)
                .padding(horizontal = 16.dp)
        ) {
            // Shop name row
            item {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Store, null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = shopName,
                        onValueChange = { shopName = it },
                        label = { Text("Shop name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Positive,
                            unfocusedBorderColor = Color(0xFF555555),
                            focusedLabelColor = Positive,
                            unfocusedLabelColor = Color(0xFF888888),
                            cursorColor = Positive
                        )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Processor: $processorName", color = Color(0xFF666666), fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF2A2A3A))
            }

            // Column header
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Item", color = Color(0xFF888888), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Price", color = Color(0xFF888888), fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
                    Spacer(Modifier.width(36.dp))
                }
                HorizontalDivider(color = Color(0xFF2A2A3A))
            }

            // Item rows
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                ReviewItemRow(
                    row = row,
                    onEdit = { editingRowId = row.id },
                    onDelete = {
                        rows = rows.toMutableList().also { it.removeAt(index) }
                    }
                )
                HorizontalDivider(color = Color(0xFF1E2229))
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // Edit dialog
    editingRowId?.let { rowId ->
        val row = rows.firstOrNull { it.id == rowId } ?: return@let
        EditItemDialog(
            initialName = row.name,
            initialPrice = row.priceText,
            onDismiss = { editingRowId = null },
            onConfirm = { name, price ->
                rows = rows.toMutableList().also { list ->
                    val idx = list.indexOfFirst { it.id == rowId }
                    if (idx >= 0) list[idx] = list[idx].copy(name = name, priceText = price, commonName = null)
                }
                editingRowId = null
            }
        )
    }

    // Add dialog
    if (showAddDialog) {
        EditItemDialog(
            initialName = "",
            initialPrice = "",
            title = "Add Item",
            onDismiss = { showAddDialog = false },
            onConfirm = { name, price ->
                val newId = (rows.maxOfOrNull { it.id } ?: -1) + 1
                rows = rows.toMutableList().also { it.add(ItemRow(newId, name, price)) }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ReviewItemRow(
    row: ItemRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val priceValid = row.price != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            val commonName = row.commonName
            if (!commonName.isNullOrBlank()) {
                Text(
                    text = commonName,
                    color = Color(0xFF6A9B6A),
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic
                )
            }
        }
        Text(
            text = if (priceValid) "€%.2f".format(row.price) else row.priceText,
            color = if (priceValid) Positive else Negative,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(80.dp)
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Delete, "Delete", tint = Color(0xFF555555), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EditItemDialog(
    initialName: String,
    initialPrice: String,
    title: String = "Edit Item",
    onDismiss: () -> Unit,
    onConfirm: (name: String, price: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var price by remember { mutableStateOf(initialPrice) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(title, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors()
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price (e.g. 1,99)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && price.isNotBlank()) onConfirm(name.trim(), price.trim())
            }) { Text("OK", color = Positive) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF888888)) }
        }
    )
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Positive,
    unfocusedBorderColor = Color(0xFF555555),
    focusedLabelColor = Positive,
    unfocusedLabelColor = Color(0xFF888888),
    cursorColor = Positive
)
