package com.example.budgettracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.ui.theme.BackgroundDark
import com.example.budgettracker.ui.theme.CardDark
import com.example.budgettracker.ui.theme.Positive

// ---------------------------------------------------------------------------
// Changelog data
// ---------------------------------------------------------------------------

private data class ChangelogEntry(
    val version: String,
    val date: String,
    val title: String,
    val changes: List<String>
)

private val CHANGELOG: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        version = "1.2.0",
        date = "2026-09-28",
        title = "Receipt Pipeline",
        changes = listOf(
            "Share receipt images (PNG/JPG) or PDFs directly into the app",
            "MLKit OCR for images; pdfbox for PDF text extraction",
            "Level 0 intermediate JSON representation preserves token positions",
            "Processor configs are JSON files, not code — swap them without rebuilding",
            "Built-in processors for Lidl, Müller, Rewe and Penny",
            "Auto-select processor by shop name when adding a receipt",
            "LLM-assisted processor generation (Gemini free tier by default)",
            "Configurable LLM API key and endpoint URL in Settings",
            "Editable receipt review table — fix item names and prices before saving",
            "Comma and period both work as decimal separators (German locale support)",
            "Receipt detail view for any past expense — edit or delete individual items",
            "Reprocess any stored receipt with a different processor",
            "Manage processors screen — view JSON, import from clipboard, delete, copy LLM prompt",
            "Export and import all data (expenses, receipts, shops) as a single JSON file"
        )
    ),
    ChangelogEntry(
        version = "1.1.0",
        date = "2026-09",
        title = "Shop Management",
        changes = listOf(
            "Shop management screen — add, edit and delete shops",
            "Shop logos fetched automatically via Clearbit",
            "Pin shop locations on an osmdroid map (no API key required)",
            "Multiple locations per shop for multi-branch stores",
            "GPS-based nearby-shop detection when logging an expense",
            "Shop name shown on every expense row and in month detail"
        )
    ),
    ChangelogEntry(
        version = "1.0.0",
        date = "2026-09",
        title = "Initial Release",
        changes = listOf(
            "Monthly budget limit with per-day allowance calculation",
            "Balance = days elapsed × daily rate − total spent",
            "Add expenses manually with an amount and optional note",
            "Daily spending bar chart with tap-to-expand day",
            "Month history screen listing all recorded months",
            "Month detail with collapsible day groups",
            "Edit and delete individual expenses",
            "Clear all entries for the current month"
        )
    )
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Changelog",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            items(CHANGELOG) { entry ->
                ChangelogCard(entry)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ChangelogCard(entry: ChangelogEntry) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Version badge + title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "v${entry.version}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Positive
                    )
                    Text(
                        text = entry.title,
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = entry.date,
                    fontSize = 12.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF2A3040))
            Spacer(Modifier.height(10.dp))

            // Change bullets
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.changes.forEach { change ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("·", fontSize = 14.sp, color = Positive, fontWeight = FontWeight.Bold)
                        Text(
                            text = change,
                            fontSize = 13.sp,
                            color = Color(0xFFCCCCCC),
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }
    }
}
