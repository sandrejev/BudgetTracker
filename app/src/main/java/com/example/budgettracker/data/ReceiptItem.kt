package com.example.budgettracker.data

import androidx.room.*

@Entity(
    tableName = "receipt_items",
    foreignKeys = [ForeignKey(
        entity = Receipt::class,
        parentColumns = ["id"],
        childColumns = ["receiptId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ReceiptItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val receiptId: Long,
    val name: String,
    val totalPrice: Double,
    val qty: String? = null,
    val sortOrder: Int = 0,
    val category: String? = null
)
