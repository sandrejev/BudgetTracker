package com.example.budgettracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Expense::class, Shop::class, ShopLocation::class, Receipt::class, ReceiptItem::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun shopDao(): ShopDao
    abstract fun receiptDao(): ReceiptDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** v1 → v2: add shopName to expenses; create shops + shop_locations tables. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN shopName TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS shops (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        logoUri TEXT
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS shop_locations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        shopId INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        label TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY (shopId) REFERENCES shops(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_shop_locations_shopId ON shop_locations(shopId)"
                )
            }
        }

        /** v2 → v3: add receipts + receipt_items tables. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS receipts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        expenseId INTEGER NOT NULL,
                        shopName TEXT,
                        rawJson TEXT NOT NULL,
                        parsedJson TEXT,
                        processorId TEXT,
                        FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_receipts_expenseId ON receipts(expenseId)"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS receipt_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        receiptId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        totalPrice REAL NOT NULL,
                        qty TEXT,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (receiptId) REFERENCES receipts(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_receipt_items_receiptId ON receipt_items(receiptId)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipt_items ADD COLUMN category TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "budget.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
