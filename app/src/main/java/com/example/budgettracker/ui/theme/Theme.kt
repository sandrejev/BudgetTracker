package com.example.budgettracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    background = BackgroundDark,
    surface = CardDark,
    primary = Positive,
    onPrimary = FieldDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun BudgetTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content
    )
}
