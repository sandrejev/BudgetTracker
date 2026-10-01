package com.example.budgettracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import com.example.budgettracker.ui.icons.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import com.example.budgettracker.data.ReceiptItem
import com.example.budgettracker.data.ReceiptWithItems
import com.example.budgettracker.receipt.Level0Doc
import com.example.budgettracker.receipt.ProcessorConfig
import com.example.budgettracker.receipt.ReceiptProcessor
import com.example.budgettracker.receipt.parsePrice
import com.example.budgettracker.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptDetailScreen(
    expenseId: Long,
    viewModel: BudgetViewModel,
    onBack: () -> Unit
) {
    val receiptData by viewModel.receiptWithItemsByExpense(expenseId)
        .collectAsState(initial = null)

    var editingItem by remember { mutableStateOf<ReceiptItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showReprocessDialog by remember { mutableStateOf(false) }
    var reprocessResult by remember { mutableStateOf<String?>(null) }
    var resolvingNames by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val receipt = receiptData?.receipt
    val items = receiptData?.items ?: emptyList()
    val total = items.sumOf { it.totalPrice }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Receipt Items", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (receipt != null) {
                        // Auto-resolve common names via LLM
                        IconButton(
                            onClick = {
                                if (!resolvingNames) {
                                    resolvingNames = true
                                    scope.launch {
                                        viewModel.resolveCommonNamesWithLlm(items)
                                        resolvingNames = false
                                        reprocessResult = "✓ Common names resolved"
                                    }
                                }
                            },
                            enabled = !resolvingNames && items.isNotEmpty()
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
                        IconButton(onClick = { showReprocessDialog = true }) {
                            Icon(Icons.Filled.Refresh, "Reprocess", tint = Color(0xFF888888))
                        }
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, "Add item", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { pv ->
        if (receipt == null) {
            Box(Modifier.fillMaxSize().padding(pv), contentAlignment = Alignment.Center) {
                Text("No receipt data for this expense.", color = Color(0xFF666666))
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pv)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            item {
                Spacer(Modifier.height(12.dp))
                if (receipt.shopName != null) {
                    Text(receipt.shopName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                if (receipt.processorId != null) {
                    Text("Parser: ${receipt.processorId}", color = Color(0xFF666666), fontSize = 11.sp)
                }
                reprocessResult?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = if (it.startsWith("✓")) Positive else Negative, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Item", color = Color(0xFF888888), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Price", color = Color(0xFF888888), fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
                    Spacer(Modifier.width(36.dp))
                }
                HorizontalDivider(color = Color(0xFF2A2A3A))
            }

            // Items
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editingItem = item }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (!item.category.isNullOrBlank()) {
                            Text(
                                item.category,
                                color = Color(0xFF6A9B6A),
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    }
                    Text(
                        "€%.2f".format(item.totalPrice),
                        color = Positive,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(80.dp)
                    )
                    IconButton(
                        onClick = { viewModel.deleteReceiptItem(item) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.Delete, "Delete", tint = Color(0xFF555555), modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(color = Color(0xFF1E2229))
            }

            // Total
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total", color = Color(0xFFAAAAAA), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "€%.2f".format(total),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // Edit item dialog
    editingItem?.let { item ->
        var nameText by remember { mutableStateOf(item.name) }
        var priceText by remember { mutableStateOf("%.2f".format(item.totalPrice).replace('.', ',')) }
        AlertDialog(
            onDismissRequest = { editingItem = null },
            containerColor = CardDark,
            title = { Text("Edit Item", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nameText, onValueChange = { nameText = it },
                        label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), colors = detailFieldColors()
                    )
                    OutlinedTextField(
                        value = priceText, onValueChange = { priceText = it },
                        label = { Text("Price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(), colors = detailFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val price = parsePrice(priceText) ?: priceText.replace(',', '.').toDoubleOrNull() ?: return@TextButton
                    viewModel.updateReceiptItem(item.copy(name = nameText.trim(), totalPrice = price))
                    editingItem = null
                }) { Text("Save", color = Positive) }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) { Text("Cancel", color = Color(0xFF888888)) }
            }
        )
    }

    // Add item dialog
    if (showAddDialog) {
        receipt?.let { r ->
            var nameText by remember { mutableStateOf("") }
            var priceText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                containerColor = CardDark,
                title = { Text("Add Item", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = nameText, onValueChange = { nameText = it },
                            label = { Text("Name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), colors = detailFieldColors()
                        )
                        OutlinedTextField(
                            value = priceText, onValueChange = { priceText = it },
                            label = { Text("Price") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), colors = detailFieldColors()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val price = parsePrice(priceText) ?: priceText.replace(',', '.').toDoubleOrNull() ?: return@TextButton
                        viewModel.addReceiptItem(r.id, nameText.trim(), price)
                        showAddDialog = false
                    }) { Text("Add", color = Positive) }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = Color(0xFF888888)) }
                }
            )
        }
    }

    // Reprocess dialog
    if (showReprocessDialog && receipt != null) {
        val processors = remember { viewModel.loadProcessors() }
        AlertDialog(
            onDismissRequest = { showReprocessDialog = false },
            containerColor = CardDark,
            title = { Text("Reprocess Receipt", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select a processor to re-parse the stored Level 0 JSON:", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                    processors.forEach { config ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val doc = Level0Doc.fromJsonString(receipt.rawJson)
                                    val parsed = ReceiptProcessor.process(doc, config)
                                    viewModel.confirmReceiptImport(
                                        shopName = receipt.shopName,
                                        amount = parsed.detectedTotal ?: parsed.items.sumOf { it.price },
                                        items = parsed.items.map { Triple(it.name, it.price, null) },
                                        doc = doc,
                                        parsedReceipt = parsed
                                    )
                                    reprocessResult = "✓ Reprocessed with ${config.name}"
                                    showReprocessDialog = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF666666), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(config.name, color = Color.White, fontSize = 14.sp)
                                if (config.isBuiltIn) Text("built-in", color = Color(0xFF666666), fontSize = 11.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF252535))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showReprocessDialog = false }) { Text("Cancel", color = Color(0xFF888888)) }
            }
        )
    }
}

@Composable
private fun detailFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Positive,
    unfocusedBorderColor = Color(0xFF555555),
    focusedLabelColor = Positive,
    unfocusedLabelColor = Color(0xFF888888),
    cursorColor = Positive
)
