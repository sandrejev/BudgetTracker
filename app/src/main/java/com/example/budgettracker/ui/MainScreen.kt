package com.example.budgettracker.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.example.budgettracker.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.budgettracker.data.Expense
import com.example.budgettracker.ui.theme.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Data helpers ─────────────────────────────────────────────────────────────

private data class DayGroup(
    val date: LocalDate,
    val total: Double,
    val entries: List<Expense>
)

private sealed class ListRow {
    data class Header(val group: DayGroup) : ListRow()
    data class Entry(val expense: Expense) : ListRow()
}

private fun buildDayGroups(entries: List<Expense>): List<DayGroup> {
    val zone = ZoneId.systemDefault()
    return entries
        .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        .entries
        .sortedByDescending { it.key }
        .map { (date, exps) ->
            DayGroup(date, exps.sumOf { it.amount }, exps.sortedByDescending { it.timestamp })
        }
}

private fun buildFlatRows(groups: List<DayGroup>, expanded: Set<LocalDate>): List<ListRow> =
    buildList {
        for (g in groups) {
            add(ListRow.Header(g))
            if (g.date in expanded) g.entries.forEach { add(ListRow.Entry(it)) }
        }
    }

// ── Swipe-to-delete wrapper ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteWrapper(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.35f })
    // Stable callback so SwipeToDismissBox doesn't re-fire onDismiss on recomposition
    val currentOnDelete by rememberUpdatedState(onDelete)
    val onDismiss = remember<(SwipeToDismissBoxValue) -> Unit> {
        { value -> if (value == SwipeToDismissBoxValue.EndToStart) currentOnDelete() }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = onDismiss,
        backgroundContent = {
            // progress is 0→1 as user swipes left; use it for continuous colour+icon fade
            val progress = state.progress
            val bgAlpha = (progress * 1.3f).coerceIn(0f, 0.9f)
            val iconAlpha = ((progress - 0.12f) * 4f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Negative.copy(alpha = bgAlpha))
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (iconAlpha > 0f) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = Color.White.copy(alpha = iconAlpha),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    ) {
        Box(Modifier.background(BackgroundDark)) { content() }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    viewModel: BudgetViewModel,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReceiptDetail: ((Long) -> Unit)? = null
) {
    val stats by viewModel.currentMonthStats.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var expandedDays by remember { mutableStateOf(setOf<LocalDate>()) }
    var editingExpense by remember { mutableStateOf<Expense?>(null) }

    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var shopNameText by remember { mutableStateOf("") }
    var detectingShop by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) detectingShop = true
    }

    // Trigger GPS detection when detectingShop flips to true
    LaunchedEffect(detectingShop) {
        if (detectingShop) {
            val name = viewModel.detectNearbyShopName()
            if (!name.isNullOrBlank()) shopNameText = name
            detectingShop = false
        }
    }

    fun detectShop() {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            detectingShop = true
        } else {
            locationPermissionLauncher.launch(permission)
        }
    }

    val dayGroups = remember(stats?.entries) { buildDayGroups(stats?.entries ?: emptyList()) }
    val flatRows = remember(dayGroups, expandedDays) { buildFlatRows(dayGroups, expandedDays) }

    // Chart tap → expand day & scroll to it
    fun onChartDayTapped(dayOfMonth: Int) {
        val s = stats ?: return
        val targetDate = s.yearMonth.atDay(dayOfMonth)
        expandedDays = expandedDays + targetDate
        val headerIdx = flatRows.indexOfFirst { it is ListRow.Header && it.group.date == targetDate }
        if (headerIdx >= 0) {
            scope.launch { listState.animateScrollToItem(FIXED_HEADER_ITEMS + headerIdx) }
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Filled.Refresh, "Refresh", tint = Color(0xFF888888))
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Filled.History, "History", tint = Color(0xFF888888))
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, "Settings", tint = Color(0xFF888888))
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Item 0 – balance card
            item {
                BalanceCard(stats)
                Spacer(Modifier.height(16.dp))
            }

            // Item 1 – input
            item {
                InputSection(
                    amountText = amountText,
                    noteText = noteText,
                    shopNameText = shopNameText,
                    detectingShop = detectingShop,
                    onAmountChange = { amountText = it },
                    onNoteChange = { noteText = it },
                    onShopNameChange = { shopNameText = it },
                    onDetectShop = { detectShop() },
                    onAdd = {
                        val amount = amountText.replace(',', '.').toDoubleOrNull()
                        if (amount != null && amount > 0) {
                            viewModel.addExpense(amount, noteText.trim(), shopNameText.trim().ifBlank { null })
                            amountText = ""
                            noteText = ""
                            shopNameText = ""
                            focusManager.clearFocus()
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            // Item 2 – chart
            item {
                stats?.let { s ->
                    Text(
                        "This month",
                        fontSize = 12.sp,
                        color = Color(0xFF666666),
                        letterSpacing = 0.08.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    DailySpendingChart(
                        yearMonth = s.yearMonth,
                        entries = s.entries,
                        dailyRate = s.dailyRate,
                        onDayTapped = { ::onChartDayTapped.invoke(it) }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            // Day groups (flat list)
            items(flatRows, key = { row ->
                when (row) {
                    is ListRow.Header -> "hdr_${row.group.date}"
                    is ListRow.Entry -> "exp_${row.expense.id}"
                }
            }) { row ->
                when (row) {
                    is ListRow.Header -> {
                        DayGroupHeader(
                            date = row.group.date,
                            total = row.group.total,
                            isExpanded = row.group.date in expandedDays,
                            onToggle = {
                                expandedDays = if (row.group.date in expandedDays)
                                    expandedDays - row.group.date
                                else
                                    expandedDays + row.group.date
                            }
                        )
                    }
                    is ListRow.Entry -> {
                        SwipeToDeleteWrapper(onDelete = { viewModel.deleteExpense(row.expense) }) {
                            ExpenseRow(
                                expense = row.expense,
                                showEdit = true,
                                onDelete = { viewModel.deleteExpense(row.expense) },
                                onEdit = { editingExpense = row.expense },
                                onReceiptDetail = onOpenReceiptDetail?.let { cb -> { cb(row.expense.id) } }
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editingExpense?.let { expense ->
        EditExpenseDialog(
            expense = expense,
            onDismiss = { editingExpense = null },
            onConfirm = { amount, note, shopName ->
                viewModel.updateExpense(expense, amount, note, shopName)
                editingExpense = null
            }
        )
    }
}

// 3 fixed items: balance card, input section, chart
private const val FIXED_HEADER_ITEMS = 3

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun BalanceCard(stats: MonthStats?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "CURRENT BALANCE",
            fontSize = 12.sp,
            letterSpacing = 0.1.sp,
            color = Color(0xFF888888)
        )
        Spacer(Modifier.height(4.dp))
        if (stats == null) {
            Text("—", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Color.White)
        } else {
            val balance = stats.balance
            Text(
                text = (if (balance < 0) "-" else "") + "€%.2f".format(Math.abs(balance)),
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                color = if (balance >= 0) Positive else Negative
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Day ${LocalDate.now().dayOfMonth} · " +
                        "€%.2f allotted · €%.2f spent".format(stats.allowance, stats.spent),
                fontSize = 12.sp,
                color = Color(0xFF666666)
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Color(0xFF222830))
    }
}

@Composable
private fun InputSection(
    amountText: String,
    noteText: String,
    shopNameText: String,
    detectingShop: Boolean,
    onAmountChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onShopNameChange: (String) -> Unit,
    onDetectShop: () -> Unit,
    onAdd: () -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Positive,
        unfocusedBorderColor = Color(0xFF333333),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Positive
    )
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = amountText,
                onValueChange = onAmountChange,
                placeholder = { Text("Amount (€)", color = Color(0xFF555555)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = fieldColors
            )
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(containerColor = Positive),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Add", color = Color(0xFF0E1116), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = noteText,
            onValueChange = onNoteChange,
            placeholder = { Text("Note (optional)", color = Color(0xFF555555)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = shopNameText,
            onValueChange = onShopNameChange,
            placeholder = { Text("Shop (optional)", color = Color(0xFF555555)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (detectingShop) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Positive
                    )
                } else {
                    IconButton(onClick = onDetectShop) {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = "Detect nearby shop",
                            tint = Color(0xFF888888)
                        )
                    }
                }
            },
            colors = fieldColors
        )
    }
}

@Composable
fun DayGroupHeader(
    date: LocalDate,
    total: Double,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = date.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFBBBBBB)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatEuro(total),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFDDDDDD)
            )
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Color(0xFF666666),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ExpenseRow(
    expense: Expense,
    showEdit: Boolean = false,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onReceiptDetail: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatEuro(expense.amount),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (!expense.shopName.isNullOrBlank()) {
                Text(
                    text = expense.shopName,
                    fontSize = 11.sp,
                    color = Positive.copy(alpha = 0.75f)
                )
            }
            if (expense.note.isNotBlank()) {
                Text(
                    text = expense.note,
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
            }
        }
        Text(
            text = formatEntryTimestamp(expense.timestamp),
            fontSize = 12.sp,
            color = Color(0xFF555555),
            modifier = Modifier.padding(end = 4.dp)
        )
        // Receipt detail / edit-items button
        if (onReceiptDetail != null) {
            IconButton(onClick = onReceiptDetail, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.ReceiptLong,
                    contentDescription = "Edit receipt",
                    tint = Color(0xFF4A6FA5),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        // Edit expense (amount / note / shop)
        if (showEdit && onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit",
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        // Delete button removed — use swipe left to delete
    }
}

@Composable
fun EditExpenseDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onConfirm: (Double, String, String?) -> Unit
) {
    var amountText by remember { mutableStateOf(expense.amount.toString()) }
    var noteText by remember { mutableStateOf(expense.note) }
    var shopNameText by remember { mutableStateOf(expense.shopName ?: "") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Positive,
        focusedLabelColor = Positive,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        unfocusedBorderColor = Color(0xFF444444)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Edit expense", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (€)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = shopNameText,
                    onValueChange = { shopNameText = it },
                    label = { Text("Shop") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.replace(',', '.').toDoubleOrNull()
                if (amount != null && amount > 0) {
                    onConfirm(amount, noteText.trim(), shopNameText.trim().ifBlank { null })
                }
            }) { Text("Save", color = Positive) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF888888)) }
        }
    )
}
