package com.example.budgettracker.ui

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun formatEuro(amount: Double): String = "€%.2f".format(amount)

fun monthLabel(ym: YearMonth): String =
    ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

fun formatEntryTimestamp(ts: Long): String {
    val ldt = Instant.ofEpochMilli(ts)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    return ldt.format(DateTimeFormatter.ofPattern("HH:mm"))
}
