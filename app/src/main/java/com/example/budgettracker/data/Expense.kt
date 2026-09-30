package com.example.budgettracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val note: String,
    val timestamp: Long,
    /** Name of the shop where this expense occurred, or null if unknown. */
    val shopName: String? = null
)
