package com.example.budgettracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.data.Expense
import com.example.budgettracker.ui.theme.Negative
import com.example.budgettracker.ui.theme.Positive
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@Composable
fun DailySpendingChart(
    yearMonth: YearMonth,
    entries: List<Expense>,
    dailyRate: Double,
    modifier: Modifier = Modifier,
    onDayTapped: ((Int) -> Unit)? = null
) {
    val zone = ZoneId.systemDefault()
    val daysInMonth = yearMonth.lengthOfMonth()

    val spendingByDay: Map<Int, Double> = remember(entries) {
        entries.groupBy { e ->
            Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate().dayOfMonth
        }.mapValues { (_, list) -> list.sumOf { it.amount } }
    }

    val maxSpending = spendingByDay.values.maxOrNull() ?: 0.0
    val chartMax = maxOf(maxSpending, dailyRate) * 1.25

    var selectedDay by remember { mutableStateOf<Int?>(null) }
    var canvasWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(selectedDay) {
        if (selectedDay != null) {
            delay(3000)
            selectedDay = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .onSizeChanged { canvasWidthPx = it.width }
    ) {
        val totalWidthDp = with(density) { canvasWidthPx.toDp() }

        // Bar chart canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(daysInMonth) {
                    detectTapGestures { offset ->
                        val day = ((offset.x / size.width * daysInMonth).toInt() + 1)
                            .coerceIn(1, daysInMonth)
                        selectedDay = day
                        onDayTapped?.invoke(day)
                    }
                }
        ) {
            if (chartMax <= 0) return@Canvas

            val barWidth = size.width / daysInMonth
            val chartHeight = size.height - 28f   // reserve top 28px for tooltip

            // Reference line (daily rate)
            val refY = 28f + chartHeight - (dailyRate / chartMax * chartHeight).toFloat()
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, refY),
                end = Offset(size.width, refY),
                strokeWidth = 2f
            )

            // Bars
            for (day in 1..daysInMonth) {
                val amount = spendingByDay[day] ?: 0.0
                if (amount <= 0) continue
                val barHeight = (amount / chartMax * chartHeight).toFloat()
                val left = (day - 1) * barWidth + barWidth * 0.1f
                val top = 28f + chartHeight - barHeight
                val alpha = when {
                    selectedDay == null -> 0.82f
                    selectedDay == day  -> 1.00f
                    else                -> 0.38f
                }
                drawRect(
                    color = (if (amount <= dailyRate) Positive else Negative).copy(alpha = alpha),
                    topLeft = Offset(left, top),
                    size = Size(barWidth * 0.8f, barHeight)
                )
            }
        }

        // Tooltip overlay (Compose Box, no native canvas needed)
        val day = selectedDay
        if (day != null) {
            val amount = spendingByDay[day] ?: 0.0
            if (amount > 0) {
                val label = "Day $day: €${"%.2f".format(amount)}"
                // centre of the bar as a fraction of width → dp offset
                val barCentreFraction = (day - 0.5f) / daysInMonth
                val rawXDp = totalWidthDp * barCentreFraction
                // clamp so the bubble stays on screen (rough 100.dp bubble width)
                val clampedXDp = rawXDp.coerceIn(0.dp, totalWidthDp - 100.dp)

                Box(
                    modifier = Modifier
                        .offset(x = clampedXDp, y = 2.dp)
                        .background(Color(0xD0101418), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(label, color = Color.White, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}
