package com.example.budgettracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.ui.theme.BackgroundDark
import com.example.budgettracker.ui.theme.CardDark
import com.example.budgettracker.ui.theme.Positive

// ---------------------------------------------------------------------------
// Changelog data model
// ---------------------------------------------------------------------------

private data class ChangelogEntry(
    val version: String,
    val date: String,
    val title: String,
    val changes: List<String>
)

/**
 * Parses changelog.md from assets.
 * Each section starts with: ## vX.Y.Z — YYYY-MM-DD — Title
 * Change lines start with: - text
 */
private fun parseChangelog(md: String): List<ChangelogEntry> {
    val headerRegex = Regex("""^##\s+v(\S+)\s+[—-]+\s+(\S+)\s+[—-]+\s+(.+)$""")
    val entries = mutableListOf<ChangelogEntry>()
    var currentVersion = ""
    var currentDate = ""
    var currentTitle = ""
    val currentChanges = mutableListOf<String>()

    fun flush() {
        if (currentVersion.isNotEmpty()) {
            entries.add(ChangelogEntry(currentVersion, currentDate, currentTitle, currentChanges.toList()))
            currentChanges.clear()
        }
    }

    for (line in md.lines()) {
        val trimmed = line.trim()
        val headerMatch = headerRegex.matchEntire(trimmed)
        if (headerMatch != null) {
            flush()
            currentVersion = headerMatch.groupValues[1]
            currentDate = headerMatch.groupValues[2]
            currentTitle = headerMatch.groupValues[3].trim()
        } else if (trimmed.startsWith("- ") && currentVersion.isNotEmpty()) {
            currentChanges.add(trimmed.removePrefix("- ").trim())
        }
    }
    flush()
    return entries
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val changelog = remember {
        try {
            val text = context.assets.open("changelog.md").bufferedReader().use { it.readText() }
            parseChangelog(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

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
        if (changelog.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text("No changelog available.", color = Color(0xFF666666))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            items(changelog) { entry ->
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
