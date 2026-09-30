package com.example.budgettracker.data

import kotlinx.coroutines.flow.Flow

class ReceiptRepository(private val dao: ReceiptDao) {

    fun receiptWithItemsByExpense(expenseId: Long): Flow<ReceiptWithItems?> =
        dao.getReceiptWithItemsByExpense(expenseId)

    fun receiptWithItemsById(receiptId: Long): Flow<ReceiptWithItems?> =
        dao.getReceiptWithItemsById(receiptId)

    fun allReceipts(): Flow<List<Receipt>> = dao.getAllReceipts()

    suspend fun insertReceipt(receipt: Receipt): Long = dao.insertReceipt(receipt)

    suspend fun updateReceipt(receipt: Receipt) = dao.updateReceipt(receipt)

    suspend fun deleteReceipt(receipt: Receipt) = dao.deleteReceipt(receipt)

    suspend fun getReceiptByExpense(expenseId: Long): Receipt? =
        dao.getReceiptByExpense(expenseId)

    suspend fun replaceItems(receiptId: Long, items: List<ReceiptItem>) {
        dao.deleteItemsForReceipt(receiptId)
        dao.insertItems(items.mapIndexed { i, item -> item.copy(receiptId = receiptId, sortOrder = i) })
    }

    suspend fun insertItem(item: ReceiptItem): Long = dao.insertItem(item)

    suspend fun updateItem(item: ReceiptItem) = dao.updateItem(item)

    suspend fun deleteItem(item: ReceiptItem) = dao.deleteItem(item)

    fun itemsForReceipt(receiptId: Long): Flow<List<ReceiptItem>> =
        dao.getItemsForReceipt(receiptId)

    suspend fun getAllReceiptsWithItemsOnce(): List<ReceiptWithItems> =
        dao.getAllReceiptsWithItemsOnce()
}
