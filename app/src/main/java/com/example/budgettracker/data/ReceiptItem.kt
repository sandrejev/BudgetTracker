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
    /** Paid for all units together, after [discount]. */
    val totalPrice: Double,
    /** Number of units bought, e.g. "2" or "0.436" (kg); null (older items) means 1. See [quantity]. */
    val qty: String? = null,
    val sortOrder: Int = 0,
    /**
     * Shared alias for [name]; its common name and category apply to every receipt
     * line with the same text (see ItemNames.kt).
     */
    @ColumnInfo(index = true) val aliasId: Long? = null,
    /** Total discount for all units (positive amount), already subtracted from [totalPrice]. */
    val discount: Double? = null
) {
    val quantity: Double get() = qty?.replace(',', '.')?.toDoubleOrNull() ?: 1.0

    companion object {
        /** Quantity text for [qty]: "2" for whole numbers, otherwise e.g. "0.436". */
        fun qtyText(quantity: Double): String =
            if (quantity == Math.floor(quantity)) quantity.toLong().toString() else quantity.toString()
    }
}
