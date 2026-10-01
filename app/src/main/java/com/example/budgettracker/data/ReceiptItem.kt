package com.example.budgettracker.data

import androidx.room.*

@Entity(
    tableName = "receipt_items",
    foreignKeys = [
        ForeignKey(
            entity = Receipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ItemAlias::class,
            parentColumns = ["id"],
            childColumns = ["aliasId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ReceiptItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val receiptId: Long,
    /** Item text as printed on the receipt. */
    val name: String,
    val totalPrice: Double,
    val qty: String? = null,
    val sortOrder: Int = 0,
    /**
     * Shared alias for [name]; its common name and category apply to every receipt
     * line with the same text (see ItemNames.kt).
     */
    @ColumnInfo(index = true) val aliasId: Long? = null
)
