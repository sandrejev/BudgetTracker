package com.example.budgettracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {

    // ── Receipt queries ────────────────────────────────────────────────────────

    @Transaction
    @Query("SELECT * FROM receipts WHERE expenseId = :expenseId LIMIT 1")
    fun getReceiptWithItemsByExpense(expenseId: Long): Flow<ReceiptWithItems?>

    @Transaction
    @Query("SELECT * FROM receipts WHERE id = :receiptId LIMIT 1")
    fun getReceiptWithItemsById(receiptId: Long): Flow<ReceiptWithItems?>

    @Query("SELECT * FROM receipts")
    fun getAllReceipts(): Flow<List<Receipt>>

    @Insert
    suspend fun insertReceipt(receipt: Receipt): Long

    @Update
    suspend fun updateReceipt(receipt: Receipt)

    @Delete
    suspend fun deleteReceipt(receipt: Receipt)

    @Query("SELECT * FROM receipts WHERE expenseId = :expenseId LIMIT 1")
    suspend fun getReceiptByExpense(expenseId: Long): Receipt?

    // ── Item queries ──────────────────────────────────────────────────────────

    @Insert
    suspend fun insertItem(item: ReceiptItem): Long

    @Insert
    suspend fun insertItems(items: List<ReceiptItem>)

    @Update
    suspend fun updateItem(item: ReceiptItem)

    @Delete
    suspend fun deleteItem(item: ReceiptItem)

    @Query("DELETE FROM receipt_items WHERE receiptId = :receiptId")
    suspend fun deleteItemsForReceipt(receiptId: Long)

    @Query("SELECT * FROM receipt_items WHERE receiptId = :receiptId ORDER BY sortOrder ASC")
    fun getItemsForReceipt(receiptId: Long): Flow<List<ReceiptItem>>

    // ── Export ────────────────────────────────────────────────────────────────

    @Transaction
    @Query("SELECT * FROM receipts")
    suspend fun getAllReceiptsWithItemsOnce(): List<ReceiptWithItems>
}
