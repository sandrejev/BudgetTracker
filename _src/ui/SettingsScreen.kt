package com.example.budgettracker.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BudgetViewModel,
    onBack: () -> Unit,
    onOpenShopManagement: () -> Unit = {},
    onOpenProcessorManagement: () -> Unit = {},
    onOpenChangelog: () -> Unit = {}
) {
    val monthlyLimit by viewModel.monthlyLimit.collectAsState()
    var limitText by remember(monthlyLimit) { mutableStateOf(monthlyLimit.toInt().toString()) }
    var saved by remember { mutableStateOf(false) }

    val llmApiKey by viewModel.llmApiKey.collectAsState()
    val llmApiUrl by viewModel.llmApiUrl.collectAsState()
    var apiKeyText by remember(llmApiKey) { mutableStateOf(llmApiKey) }
    var apiUrlText by remember(llmApiUrl) { mutableStateOf(llmApiUrl) }
    var showApiKey by remember { mutableStateOf(false) }
    var llmSaved by remember { mutableStateOf(false) }

    var testingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val today = LocalDate.now()
    val ym = YearMonth.of(today.year, today.month)
    val daysInMonth = ym.lengthOfMonth()
    val daysRemaining = daysInMonth - today.dayOfMonth + 1
    val parsedLimit = limitText.toDoubleOrNull() ?: monthlyLimit
    val dailyRate = parsedLimit / daysInMonth

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Monthly limit ──────────────────────────────────────────────────
            Text("Monthly limit", fontSize = 13.sp, color = Color(0xFF888888), letterSpacing = 0.06.sp)

            OutlinedTextField(
                value = limitText,
                onValueChange = { limitText = it; saved = false },
                label = { Text("€ per month") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = settingsFieldColors()
            )

            Button(
                onClick = {
                    parsedLimit.takeIf { it > 0 }?.let {
                        viewModel.setMonthlyLimit(it)
                        saved = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Positive),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Save", color = Color(0xFF0E1116), fontWeight = FontWeight.Bold)
            }

            if (saved) {
                Text("Saved ✓", fontSize = 13.sp, color = Positive)
            }

            HorizontalDivider(color = Color(0xFF222830))

            // ── Info rows ──────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Daily allowance", "€%.2f / day".format(dailyRate))
                InfoRow("Days in month", "$daysInMonth days")
                InfoRow("Days remaining", "$daysRemaining days")
            }

            Text(
                "Your daily budget is calculated as monthly limit ÷ days in the month. " +
                        "Balance = days elapsed × daily rate − total spent.",
                fontSize = 12.sp,
                color = Color(0xFF555555),
                lineHeight = 18.sp
            )

            HorizontalDivider(color = Color(0xFF222830))

            // ── LLM settings ──────────────────────────────────────────────────
            Text("LLM receipt parsing", fontSize = 13.sp, color = Color(0xFF888888), letterSpacing = 0.06.sp)

            Text(
                "Used to auto-generate a processor config when no matching parser is found, " +
                        "and to suggest item categories on the review screen. " +
                        "Defaults to Gemini 1.5 Flash (free tier). Leave URL blank to use the default.",
                fontSize = 12.sp,
                color = Color(0xFF555555),
                lineHeight = 18.sp
            )

            // Clickable hint for getting a key
            val linkText = buildAnnotatedString {
                append("Get a free Gemini API key at ")
                withStyle(SpanStyle(color = Color(0xFF4A8EFF), textDecoration = TextDecoration.Underline)) {
                    append("aistudio.google.com")
                }
            }
            Text(
                text = linkText,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                    context.startActivity(intent)
                }
            )

            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it; llmSaved = false; testResult = null },
                label = { Text("API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showApiKey) "Hide" else "Show",
                            tint = Color(0xFF666666)
                        )
                    }
                },
                colors = settingsFieldColors()
            )

            OutlinedTextField(
                value = apiUrlText,
                onValueChange = { apiUrlText = it; llmSaved = false; testResult = null },
                label = { Text("API URL (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://generativelanguage.googleapis.com/…", color = Color(0xFF444444), fontSize = 11.sp) },
                colors = settingsFieldColors()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Test connection button
                OutlinedButton(
                    onClick = {
                        testResult = null
                        scope.launch {
                            testingConnection = true
                            testResult = viewModel.testLlmConnection(apiKeyText.trim(), apiUrlText.trim())
                            testingConnection = false
                        }
                    },
                    enabled = !testingConnection,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF888888)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444))
                ) {
                    if (testingConnection) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF888888))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Test", fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        viewModel.setLlmApiKey(apiKeyText.trim())
                        viewModel.setLlmApiUrl(apiUrlText.trim())
                        llmSaved = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Positive)
                ) {
                    Text("Save", color = Color(0xFF0E1116), fontWeight = FontWeight.Bold)
                }
            }

            // Test result or save confirmation
            testResult?.let { result ->
                Text(
                    result,
                    fontSize = 13.sp,
                    color = if (result.startsWith("✓")) Positive else Negative,
                    lineHeight = 18.sp
                )
            }
            if (llmSaved && testResult == null) {
                Text("Saved ✓", fontSize = 13.sp, color = Positive)
            }

            HorizontalDivider(color = Color(0xFF222830))

            // ── Shop management ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenShopManagement)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Store, null, tint = Color(0xFF888888), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Manage shops", fontSize = 15.sp, color = Color.White)
                    Text("Add shops and their locations for auto-detection", fontSize = 12.sp, color = Color(0xFF666666))
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF555555))
            }

            HorizontalDivider(color = Color(0xFF222830))

            // ── Processor management ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProcessorManagement)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Code, null, tint = Color(0xFF888888), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Manage processors", fontSize = 15.sp, color = Color.White)
                    Text("View, import and delete receipt parser configs", fontSize = 12.sp, color = Color(0xFF666666))
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF555555))
            }

            HorizontalDivider(color = Color(0xFF222830))

            // ── Changelog ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenChangelog)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.History, null, tint = Color(0xFF888888), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Changelog", fontSize = 15.sp, color = Color.White)
                    Text("What's new in each version", fontSize = 12.sp, color = Color(0xFF666666))
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF555555))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF888888))
        Text(value, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Positive,
    focusedLabelColor = Positive,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    unfocusedBorderColor = Color(0xFF444444),
    cursorColor = Positive
)
