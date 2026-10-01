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

/** "2" for whole numbers, otherwise e.g. "0,436". */
fun formatQuantity(quantity: Double): String =
    if (quantity == Math.floor(quantity)) quantity.toLong().toString()
    else "%.3f".format(quantity).trimEnd('0').replace('.', ',')
