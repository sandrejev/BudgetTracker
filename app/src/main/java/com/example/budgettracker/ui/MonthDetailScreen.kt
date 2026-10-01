package com.example.budgettracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.data.Expense
import com.example.budgettracker.ui.theme.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class DayGroupDetail(
    val date: LocalDate,
    val total: Double,
    val entries: List<Expense>
)

private sealed class DetailRow {
    data class Header(val group: DayGroupDetail) : DetailRow()
    data class Entry(val expense: Expense) : DetailRow()
}

private fun buildDetailDayGroups(entries: List<Expense>): List<DayGroupDetail> {
    val zone = ZoneId.systemDefault()
    return entries
        .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        .entries
        .sortedByDescending { it.key }
        .map { (date, exps) ->
            DayGroupDetail(date, exps.sumOf { it.amount }, exps.sortedByDescending { it.timestamp })
        }
}

private fun buildDetailFlatRows(groups: List<DayGroupDetail>, expanded: Set<LocalDate>): List<DetailRow> =
    buildList {
        for (g in groups) {
            add(DetailRow.Header(g))
            if (g.date in expanded) g.entries.forEach { add(DetailRow.Entry(it)) }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthDetailScreen(
    viewModel: BudgetViewModel,
    yearMonth: YearMonth,
    onBack: () -> Unit,
    onOpenReceiptDetail: ((expenseId: Long) -> Unit)? = null
) {
    val stats by remember(yearMonth) { viewModel.statsForMonth(yearMonth) }
        .collectAsState(initial = null)

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var expandedDays by remember { mutableStateOf(setOf<LocalDate>()) }
    var editingExpense by remember { mutableStateOf<Expense?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val dayGroups = remember(stats?.entries) { buildDetailDayGroups(stats?.entries ?: emptyList()) }
    val flatRows = remember(dayGroups, expandedDays) { buildDetailFlatRows(dayGroups, expandedDays) }

    // Chart tap: expand day & scroll to it (2 fixed items: header-spacer, chart)
    fun onChartDayTapped(dayOfMonth: Int) {
        val s = stats ?: return
        val targetDate = s.yearMonth.atDay(dayOfMonth)
        expandedDays = expandedDays + targetDate
        val headerIdx = flatRows.indexOfFirst {
            it is DetailRow.Header && it.group.date == targetDate
        }
        if (headerIdx >= 0) {
            scope.launch { listState.animateScrollToItem(2 + headerIdx) }
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = monthLabel(yearMonth),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (stats?.isCurrent == true) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear month", tint = Negative)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Item 0 – summary header
            item {
                stats?.let { s ->
                    MonthSummaryHeader(s)
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Item 1 – chart
            item {
                stats?.let { s ->
                    DailySpendingChart(
                        yearMonth = s.yearMonth,
                        entries = s.entries,
                        dailyRate = s.dailyRate,
                        onDayTapped = { ::onChartDayTapped.invoke(it) }
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color(0xFF222830))
                    Spacer(Modifier.height(4.dp))
                }
            }

            // Day groups
            items(flatRows, key = { row ->
                when (row) {
                    is DetailRow.Header -> "hdr_${row.group.date}"
                    is DetailRow.Entry -> "exp_${row.expense.id}"
                }
            }) { row ->
                when (row) {
                    is DetailRow.Header -> {
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
                    is DetailRow.Entry -> {
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
            shopNames = viewModel.shops.collectAsState().value.map { it.name },
            onDismiss = { editingExpense = null },
            onConfirm = { amount, note, shopName ->
                viewModel.updateExpense(expense, amount, note, shopName)
                editingExpense = null
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = CardDark,
            title = { Text("Clear month?", color = Color.White) },
            text = { Text("This will delete all entries for ${monthLabel(yearMonth)}.", color = Color(0xFFAAAAAA)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearMonth(yearMonth)
                    showClearConfirm = false
                }) { Text("Delete all", color = Negative) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel", color = Color(0xFF888888)) }
            }
        )
    }
}

@Composable
private fun MonthSummaryHeader(stats: MonthStats) {
    val balance = stats.balance
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = (if (balance < 0) "-" else "") + "€%.2f".format(Math.abs(balance)),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = if (balance >= 0) Positive else Negative
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (stats.isCurrent)
                "Day ${java.time.LocalDate.now().dayOfMonth} of ${stats.yearMonth.lengthOfMonth()} · " +
                        "€%.2f spent".format(stats.spent)
            else
                "€%.2f allotted · €%.2f spent".format(stats.allowance, stats.spent),
            fontSize = 13.sp,
            color = Color(0xFF888888)
        )
    }
}
