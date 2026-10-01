package com.example.budgettracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Expense::class, Shop::class, ShopLocation::class, Receipt::class, ReceiptItem::class,
        ItemCategory::class, CommonName::class, ItemAlias::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun shopDao(): ShopDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun itemNameDao(): ItemNameDao

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

        /** v3 → v4: add category column to receipt_items. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipt_items ADD COLUMN category TEXT")
            }
        }

        /** v4 → v5: add common_names table for LLM-resolved product name pool. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS common_names (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        usageCount INTEGER NOT NULL DEFAULT 1
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_common_names_name ON common_names(name)"
                )
            }
        }

        /**
         * v5 → v6: common names become shared and editable.
         * - New item_categories (pre-filled) and item_aliases tables.
         * - common_names gets a categoryId.
         * - receipt_items: the per-item common name text (column "category") is replaced by
         *   aliasId; the existing texts are moved into common_names/item_aliases.
         * Tables are re-created so the schema matches the entities exactly.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS item_categories (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, sortOrder INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_item_categories_name ON item_categories (name)")
                DefaultCategories.insertInto(db)

                // common_names + categoryId
                db.execSQL(
                    "CREATE TABLE common_names_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                        "categoryId INTEGER, usageCount INTEGER NOT NULL, " +
                        "FOREIGN KEY(categoryId) REFERENCES item_categories(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL(
                    "INSERT INTO common_names_new (id, name, categoryId, usageCount) " +
                        "SELECT id, name, NULL, usageCount FROM common_names"
                )
                db.execSQL("DROP TABLE common_names")
                db.execSQL("ALTER TABLE common_names_new RENAME TO common_names")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_common_names_name ON common_names (name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_common_names_categoryId ON common_names (categoryId)")

                // Common names that so far only existed as text on receipt items
                db.execSQL(
                    "INSERT INTO common_names (name, categoryId, usageCount) " +
                        "SELECT MIN(TRIM(category)), NULL, 0 FROM receipt_items " +
                        "WHERE category IS NOT NULL AND TRIM(category) <> '' " +
                        "AND NOT EXISTS (SELECT 1 FROM common_names cn " +
                        "    WHERE cn.name = TRIM(receipt_items.category) COLLATE NOCASE) " +
                        "GROUP BY LOWER(TRIM(category))"
                )

                // One alias per distinct item text, linked to the common name most recently
                // stored for that text
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS item_aliases (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, rawName TEXT NOT NULL, " +
                        "commonNameId INTEGER, " +
                        "FOREIGN KEY(commonNameId) REFERENCES common_names(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_item_aliases_rawName ON item_aliases (rawName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_item_aliases_commonNameId ON item_aliases (commonNameId)")
                db.execSQL(
                    "INSERT INTO item_aliases (rawName, commonNameId) " +
                        "SELECT TRIM(ri.name), (" +
                        "    SELECT cn.id FROM common_names cn WHERE cn.name = (" +
                        "        SELECT TRIM(r2.category) FROM receipt_items r2 " +
                        "        WHERE TRIM(r2.name) = TRIM(ri.name) " +
                        "        AND r2.category IS NOT NULL AND TRIM(r2.category) <> '' " +
                        "        ORDER BY r2.id DESC LIMIT 1) COLLATE NOCASE " +
                        "    ORDER BY cn.id LIMIT 1) " +
                        "FROM receipt_items ri WHERE TRIM(ri.name) <> '' GROUP BY TRIM(ri.name)"
                )

                // receipt_items: category text → aliasId
                db.execSQL(
                    "CREATE TABLE receipt_items_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, receiptId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, totalPrice REAL NOT NULL, qty TEXT, sortOrder INTEGER NOT NULL, " +
                        "aliasId INTEGER, " +
                        "FOREIGN KEY(receiptId) REFERENCES receipts(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(aliasId) REFERENCES item_aliases(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL(
                    "INSERT INTO receipt_items_new (id, receiptId, name, totalPrice, qty, sortOrder, aliasId) " +
                        "SELECT id, receiptId, name, totalPrice, qty, sortOrder, " +
                        "(SELECT a.id FROM item_aliases a WHERE a.rawName = TRIM(receipt_items.name)) " +
                        "FROM receipt_items"
                )
                db.execSQL("DROP TABLE receipt_items")
                db.execSQL("ALTER TABLE receipt_items_new RENAME TO receipt_items")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_items_receiptId ON receipt_items (receiptId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_items_aliasId ON receipt_items (aliasId)")
            }
        }

        /** v6 → v7: receipt items get the discount printed below them. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipt_items ADD COLUMN discount REAL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "budget.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .addCallback(object : Callback() {
                        // Fresh install: start with everyday grocery categories
                        override fun onCreate(db: SupportSQLiteDatabase) = DefaultCategories.insertInto(db)
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
