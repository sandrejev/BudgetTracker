package com.example.budgettracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import com.example.budgettracker.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.receipt.Level0Doc
import com.example.budgettracker.receipt.ProcessorConfig
import com.example.budgettracker.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessorManagementScreen(
    viewModel: BudgetViewModel,
    onBack: () -> Unit
) {
    var processors by remember { mutableStateOf(viewModel.loadProcessors()) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf<ProcessorConfig?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }

    fun reload() { processors = viewModel.loadProcessors() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Manage Processors", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Filled.Add, "Import processor", tint = Color.White)
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
        ) {
            importResult?.let {
                Text(
                    it,
                    color = if (it.startsWith("✓")) Positive else Negative,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 13.sp
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(processors, key = { it.id }) { config ->
                    ProcessorRow(
                        config = config,
                        onViewJson = { showJsonDialog = config },
                        onDelete = if (config.isBuiltIn) null else ({
                            viewModel.deleteProcessor(config)
                            reload()
                        })
                    )
                    HorizontalDivider(color = Color(0xFF1E2229))
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "Built-in processors cannot be deleted.",
                            color = Color(0xFF666666),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "To add a processor for a new shop:",
                            color = Color(0xFF888888),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "1. Share a receipt from that shop to BudgetTracker\n" +
                                "2. On the processor selection screen, tap \"Generate with LLM\"\n" +
                                "   — or tap \"Copy prompt\" to paste into ChatGPT / Gemini\n" +
                                "3. Paste the JSON here with the + button",
                            color = Color(0xFF666666),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    // View JSON dialog
    showJsonDialog?.let { config ->
        AlertDialog(
            onDismissRequest = { showJsonDialog = null },
            containerColor = CardDark,
            title = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(config.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = {
                        viewModel.copyToClipboard(config.toJsonString(), "Processor Config")
                        showJsonDialog = null
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ContentCopy, "Copy", tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                    }
                }
            },
            text = {
                Box(
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        config.toJsonString(),
                        color = Color(0xFFCCCCCC),
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showJsonDialog = null }) { Text("Close", color = Positive) }
            }
        )
    }

    // Import JSON dialog
    if (showImportDialog) {
        var jsonText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = CardDark,
            title = { Text("Import Processor", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Paste the processor JSON config below.",
                        color = Color(0xFF888888),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp),
                        placeholder = { Text("{ \"id\": \"...\", ... }", color = Color(0xFF555555)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color(0xFFCCCCCC),
                            focusedBorderColor = Positive,
                            unfocusedBorderColor = Color(0xFF555555),
                            cursorColor = Positive
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = viewModel.importProcessorFromJson(jsonText.trim())
                    if (result.isSuccess) {
                        importResult = "✓ Imported \"${result.getOrNull()?.name}\""
                        reload()
                    } else {
                        importResult = "✗ Error: ${result.exceptionOrNull()?.message}"
                    }
                    showImportDialog = false
                }) { Text("Import", color = Positive) }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel", color = Color(0xFF888888)) }
            }
        )
    }
}

@Composable
private fun ProcessorRow(
    config: ProcessorConfig,
    onViewJson: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onViewJson() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(config.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (config.isBuiltIn) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "built-in",
                        color = Color(0xFF666666),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(Color(0xFF252535), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                "id: ${config.id}",
                color = Color(0xFF666666),
                fontSize = 12.sp
            )
        }
        IconButton(onClick = onViewJson) {
            Icon(Icons.Filled.Code, "View JSON", tint = Color(0xFF666666))
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete", tint = Negative)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

