package com.example.budgettracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.example.budgettracker.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.budgettracker.receipt.Level0Doc
import com.example.budgettracker.receipt.ProcessorConfig
import com.example.budgettracker.ui.theme.*

@Composable
fun SelectProcessorDialog(
    doc: Level0Doc,
    detectedShop: String?,
    processors: List<ProcessorConfig>,
    llmApiKeySet: Boolean,
    preselectedProcessor: ProcessorConfig?,
    onSelect: (ProcessorConfig) -> Unit,
    onGenerateWithLlm: () -> Unit,
    onCopyPrompt: () -> Unit,
    onCopyJson: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedProcessor by remember { mutableStateOf(preselectedProcessor) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(16.dp))
                .background(CardDark)
        ) {
            Column {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Select Parser",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp
                        )
                        if (!detectedShop.isNullOrBlank()) {
                            Text(
                                "Detected: $detectedShop",
                                color = Positive.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "Cancel", tint = Color(0xFF888888))
                    }
                }

                HorizontalDivider(color = Color(0xFF2A2A3A))

                // Processor list
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (processors.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No processors available.\nUse LLM to generate one.",
                                color = Color(0xFF666666),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        processors.forEach { config ->
                            ProcessorCard(
                                config = config,
                                isSelected = selectedProcessor?.name == config.name,
                                onClick = { selectedProcessor = config }
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2A2A3A))

                // Action buttons
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // LLM generate option
                    if (llmApiKeySet) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onGenerateWithLlm()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4A8EFF)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A4A7F))
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Generate parser with LLM", fontSize = 13.sp)
                        }
                    }

                    // Copy prompt / Copy JSON (debug options)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onCopyPrompt() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF888888)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Prompt", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { onCopyJson() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF888888)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.DataObject, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("JSON", fontSize = 12.sp)
                        }
                    }

                    // Continue button
                    Button(
                        onClick = {
                            selectedProcessor?.let { onSelect(it) }
                        },
                        enabled = selectedProcessor != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Positive,
                            disabledContainerColor = Color(0xFF1E3A2F)
                        )
                    ) {
                        Text(
                            "Continue",
                            color = if (selectedProcessor != null) Color(0xFF0E1116) else Color(0xFF444444),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessorCard(
    config: ProcessorConfig,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Positive else Color(0xFF2A2A3A)
    val bgColor = if (isSelected) Color(0xFF1E3A2F) else Color(0xFF1A1E24)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Radio indicator
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) Positive else Color(0xFF333333))
                .border(
                    1.5.dp,
                    if (isSelected) Positive else Color(0xFF444444),
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF0E1116))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                config.name,
                color = if (isSelected) Color.White else Color(0xFFCCCCCC),
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            val tags = buildList {
                if (config.isBuiltIn) add("built-in")
            }
            if (tags.isNotEmpty()) {
                Text(
                    tags.joinToString(" · "),
                    color = if (isSelected) Positive.copy(alpha = 0.7f) else Color(0xFF666666),
                    fontSize = 11.sp
                )
            }
        }

        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Positive,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
