package com.example.budgettracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.ui.theme.BackgroundDark
import com.example.budgettracker.ui.theme.CardDark
import com.example.budgettracker.ui.theme.Negative
import kotlinx.coroutines.launch

/**
 * Swipe left to delete. With [confirmTitle] set, a dialog asks first and the row
 * slides back if the user cancels (for shared data like common names or shops).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteWrapper(
    onDelete: () -> Unit,
    confirmTitle: String? = null,
    confirmText: String? = null,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.35f })
    val scope = rememberCoroutineScope()
    var askConfirm by remember { mutableStateOf(false) }
    // Stable callback so SwipeToDismissBox doesn't re-fire onDismiss on recomposition
    val currentOnDelete by rememberUpdatedState(onDelete)
    val needsConfirm by rememberUpdatedState(confirmTitle != null)
    val onDismiss = remember<(SwipeToDismissBoxValue) -> Unit> {
        { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                if (needsConfirm) askConfirm = true else currentOnDelete()
            }
        }
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

    if (askConfirm && confirmTitle != null) {
        fun cancel() {
            askConfirm = false
            scope.launch { state.reset() }
        }
        AlertDialog(
            onDismissRequest = ::cancel,
            containerColor = CardDark,
            title = { Text(confirmTitle, color = Color.White) },
            text = confirmText?.let { { Text(it, color = Color(0xFFCCCCCC), fontSize = 14.sp) } },
            confirmButton = {
                TextButton(onClick = {
                    askConfirm = false
                    currentOnDelete()
                }) { Text("Delete", color = Negative) }
            },
            dismissButton = { TextButton(onClick = ::cancel) { Text("Cancel", color = Color(0xFF888888)) } }
        )
    }
}
