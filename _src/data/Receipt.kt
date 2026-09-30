package com.example.budgettracker.data

import androidx.room.*

@Entity(
    tableName = "receipts",
    foreignKeys = [ForeignKey(
        entity = Expense::class,
        parentColumns = ["id"],
        childColumns = ["expenseId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val expenseId: Long,
    val shopName: String? = null,
    val rawJson: String,           // Level 0 JSON
    val parsedJson: String? = null, // last parsed result JSON (null until processed)
    val processorId: String? = null // which processor config was last used
)
