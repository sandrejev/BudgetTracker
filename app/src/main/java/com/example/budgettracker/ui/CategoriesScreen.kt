package com.example.budgettracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.data.CategoryWithCount
import com.example.budgettracker.data.ItemCategory
import com.example.budgettracker.ui.theme.BackgroundDark
import com.example.budgettracker.ui.theme.CardDark
import com.example.budgettracker.ui.theme.Negative
import com.example.budgettracker.ui.theme.Positive
import kotlinx.coroutines.launch

/** Settings → Item categories: add, rename and delete categories of common names. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(viewModel: BudgetViewModel, onBack: () -> Unit) {
    val categories by viewModel.categoriesWithCounts.collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CategoryWithCount?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Item categories", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, "Add category", tint = Color.White)
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
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text(
                    "Common item names are grouped into these categories. Tap one to rename it, swipe left to delete it.",
                    fontSize = 12.sp, color = Color(0xFF555555),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(categories, key = { it.id }) { category ->
                SwipeToDeleteWrapper(
                    onDelete = { viewModel.deleteCategory(category.toItemCategory()) },
                    confirmTitle = "Delete \"${category.name}\"?",
                    confirmText = "${category.commonNameCount} common name(s) in this category will have no category."
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { renaming = category }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(category.name, color = Color.White, fontSize = 15.sp)
                        Text(
                            "${category.commonNameCount} common name(s)",
                            color = Color(0xFF666666), fontSize = 11.sp
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF1E2229))
            }
        }
    }

    if (adding) {
        CategoryNameDialog(
            title = "New category",
            initialName = "",
            confirmLabel = "Add",
            onDismiss = { adding = false },
            onConfirm = { name -> viewModel.addCategory(name).map { adding = false } }
        )
    }

    renaming?.let { category ->
        CategoryNameDialog(
            title = "Rename category",
            initialName = category.name,
            confirmLabel = "Save",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.renameCategory(category.toItemCategory(), name).map { renaming = null }
            }
        )
    }
}

private fun CategoryWithCount.toItemCategory() = ItemCategory(id = id, name = name, sortOrder = sortOrder)

@Composable
private fun CategoryNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<Unit>
) {
    var name by remember { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(title, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = itemFieldColors()
                )
                error?.let { Text(it, color = Negative, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { onConfirm(name).onFailure { error = it.message } }
            }) { Text(confirmLabel, color = Positive) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF888888)) } }
    )
}
