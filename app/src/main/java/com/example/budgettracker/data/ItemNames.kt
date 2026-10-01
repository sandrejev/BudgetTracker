package com.example.budgettracker.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// How receipt items get their common name and category:
//
//   receipt_items.aliasId ──► item_aliases (raw text as printed, e.g. "PFLAUMEN LOSE")
//                                 .commonNameId ──► common_names ("plums")
//                                                      .categoryId ──► item_categories ("Fresh fruits")
//
// Every receipt line with the same raw text shares one alias, so changing an alias,
// common name or category changes it on all receipts at once. Several aliases can
// point to the same common name.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "item_categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class ItemCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0
)

/** A product as the user thinks of it, e.g. "whole milk". */
@Entity(
    tableName = "common_names",
    foreignKeys = [ForeignKey(
        entity = ItemCategory::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("name"), Index("categoryId")]
)
data class CommonName(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val categoryId: Long? = null,
    /** How often the LLM picked this name; used to offer frequent names first. */
    val usageCount: Int = 1
)

/** A raw receipt text ("BIO VOLLM. 3,8%") and the common name it converts to. */
@Entity(
    tableName = "item_aliases",
    foreignKeys = [ForeignKey(
        entity = CommonName::class,
        parentColumns = ["id"],
        childColumns = ["commonNameId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index(value = ["rawName"], unique = true), Index("commonNameId")]
)
data class ItemAlias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawName: String,
    val commonNameId: Long? = null
)

// ── Query results ────────────────────────────────────────────────────────────

data class CategoryWithCount(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val commonNameCount: Int
)

data class CommonNameRow(
    val id: Long,
    val name: String,
    val categoryId: Long?,
    val categoryName: String?,
    val aliasCount: Int,
    /** All raw texts of this name, comma-separated (for the list preview). */
    val aliasNames: String?
)

data class AliasRow(
    val id: Long,
    val rawName: String,
    val commonNameId: Long?,
    val commonName: String?,
    /** Number of receipt lines using this raw text. */
    val itemCount: Int
)

/** Common name and category of one receipt item. */
data class ItemNameInfo(
    val itemId: Long,
    val commonName: String?,
    val categoryName: String?
)

/** Common name and category known for a raw receipt text. */
data class KnownName(
    val rawName: String,
    val commonName: String,
    val categoryName: String?
)

// ── DAO ──────────────────────────────────────────────────────────────────────

@Dao
abstract class ItemNameDao {

    // Categories

    @Query(
        """SELECT c.id, c.name, c.sortOrder,
                  (SELECT COUNT(*) FROM common_names cn WHERE cn.categoryId = c.id) AS commonNameCount
           FROM item_categories c ORDER BY c.sortOrder, c.name COLLATE NOCASE"""
    )
    abstract fun categoriesWithCounts(): Flow<List<CategoryWithCount>>

    @Query("SELECT * FROM item_categories ORDER BY sortOrder, name COLLATE NOCASE")
    abstract fun categories(): Flow<List<ItemCategory>>

    @Query("SELECT * FROM item_categories ORDER BY sortOrder, name COLLATE NOCASE")
    abstract suspend fun categoriesOnce(): List<ItemCategory>

    @Query("SELECT * FROM item_categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findCategory(name: String): ItemCategory?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM item_categories")
    abstract suspend fun maxCategorySortOrder(): Int

    @Insert
    abstract suspend fun insertCategory(category: ItemCategory): Long

    @Update
    abstract suspend fun updateCategory(category: ItemCategory)

    @Delete
    abstract suspend fun deleteCategory(category: ItemCategory)

    // Common names

    @Query(
        """SELECT cn.id, cn.name, cn.categoryId, cat.name AS categoryName,
                  (SELECT COUNT(*) FROM item_aliases a WHERE a.commonNameId = cn.id) AS aliasCount,
                  (SELECT GROUP_CONCAT(a.rawName, ', ') FROM item_aliases a WHERE a.commonNameId = cn.id) AS aliasNames
           FROM common_names cn
           LEFT JOIN item_categories cat ON cat.id = cn.categoryId
           WHERE :query = ''
              OR cn.name LIKE '%' || :query || '%'
              OR EXISTS (SELECT 1 FROM item_aliases a
                         WHERE a.commonNameId = cn.id AND a.rawName LIKE '%' || :query || '%')
           ORDER BY cn.name COLLATE NOCASE
           LIMIT :limit"""
    )
    abstract fun searchCommonNames(query: String, limit: Int): Flow<List<CommonNameRow>>

    @Query("SELECT * FROM common_names WHERE id = :id")
    abstract fun commonName(id: Long): Flow<CommonName?>

    @Query("SELECT * FROM common_names WHERE id IN (:ids)")
    abstract suspend fun commonNamesOnce(ids: List<Long>): List<CommonName>

    @Query("SELECT * FROM common_names WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findCommonName(name: String): CommonName?

    @Query("SELECT name FROM common_names ORDER BY usageCount DESC, name COLLATE NOCASE LIMIT :limit")
    abstract suspend fun frequentCommonNames(limit: Int): List<String>

    @Query("SELECT name FROM common_names ORDER BY name COLLATE NOCASE")
    abstract fun commonNameStrings(): Flow<List<String>>

    @Insert
    abstract suspend fun insertCommonName(commonName: CommonName): Long

    @Update
    abstract suspend fun updateCommonName(commonName: CommonName)

    @Query("DELETE FROM common_names WHERE id IN (:ids)")
    abstract suspend fun deleteCommonNames(ids: List<Long>)

    // Aliases

    @Query("SELECT * FROM item_aliases WHERE rawName = :rawName LIMIT 1")
    abstract suspend fun findAlias(rawName: String): ItemAlias?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAlias(alias: ItemAlias): Long

    @Query("UPDATE item_aliases SET commonNameId = :commonNameId WHERE id = :aliasId")
    abstract suspend fun setAliasCommonName(aliasId: Long, commonNameId: Long?)

    @Query("UPDATE item_aliases SET commonNameId = :targetId WHERE commonNameId IN (:sourceIds)")
    abstract suspend fun moveAliases(sourceIds: List<Long>, targetId: Long)

    @Query(
        """SELECT a.id, a.rawName, a.commonNameId, cn.name AS commonName,
                  (SELECT COUNT(*) FROM receipt_items ri WHERE ri.aliasId = a.id) AS itemCount
           FROM item_aliases a LEFT JOIN common_names cn ON cn.id = a.commonNameId
           WHERE a.commonNameId = :commonNameId
           ORDER BY a.rawName COLLATE NOCASE"""
    )
    abstract fun aliasesFor(commonNameId: Long): Flow<List<AliasRow>>

    @Query(
        """SELECT a.id, a.rawName, a.commonNameId, cn.name AS commonName,
                  (SELECT COUNT(*) FROM receipt_items ri WHERE ri.aliasId = a.id) AS itemCount
           FROM item_aliases a LEFT JOIN common_names cn ON cn.id = a.commonNameId
           WHERE a.rawName LIKE '%' || :query || '%'
           ORDER BY a.rawName COLLATE NOCASE
           LIMIT 50"""
    )
    abstract suspend fun searchAliases(query: String): List<AliasRow>

    @Query(
        """SELECT a.rawName, cn.name AS commonName, cat.name AS categoryName
           FROM item_aliases a
           JOIN common_names cn ON cn.id = a.commonNameId
           LEFT JOIN item_categories cat ON cat.id = cn.categoryId
           WHERE a.rawName IN (:rawNames)"""
    )
    abstract suspend fun knownNames(rawNames: List<String>): List<KnownName>

    @Query("SELECT * FROM item_aliases ORDER BY rawName")
    abstract suspend fun aliasesOnce(): List<ItemAlias>

    @Query("SELECT * FROM common_names ORDER BY name")
    abstract suspend fun commonNamesAllOnce(): List<CommonName>

    // Receipt items

    @Query(
        """SELECT ri.id AS itemId, cn.name AS commonName, cat.name AS categoryName
           FROM receipt_items ri
           LEFT JOIN item_aliases a ON a.id = ri.aliasId
           LEFT JOIN common_names cn ON cn.id = a.commonNameId
           LEFT JOIN item_categories cat ON cat.id = cn.categoryId
           WHERE ri.receiptId = :receiptId"""
    )
    abstract fun itemNamesForReceipt(receiptId: Long): Flow<List<ItemNameInfo>>

    /** Merges [sourceIds] into [target]: their aliases move to it and they are deleted. */
    @Transaction
    open suspend fun merge(target: CommonName, sourceIds: List<Long>) {
        val others = sourceIds.filter { it != target.id }
        val usage = commonNamesOnce(others).sumOf { it.usageCount }
        moveAliases(others, target.id)
        deleteCommonNames(others)
        updateCommonName(target.copy(usageCount = target.usageCount + usage))
    }
}

// ── Default categories ───────────────────────────────────────────────────────

/** Categories for everyday grocery items, added on fresh installs and by MIGRATION_5_6. */
object DefaultCategories {
    val names = listOf(
        "Fresh fruits",
        "Fresh vegetables",
        "Bread & bakery",
        "Dairy & eggs",
        "Cheese",
        "Meat & poultry",
        "Fish & seafood",
        "Sausages & cold cuts",
        "Frozen food",
        "Pasta, rice & grains",
        "Canned & jarred food",
        "Breakfast & cereals",
        "Baking & spices",
        "Oils, sauces & condiments",
        "Sweets & snacks",
        "Coffee & tea",
        "Soft drinks & juices",
        "Water",
        "Beer & wine",
        "Spirits",
        "Baby products",
        "Pet supplies",
        "Household & cleaning",
        "Personal care",
        "Deposit (Pfand)",
        "Other"
    )

    fun insertInto(db: SupportSQLiteDatabase) {
        names.forEachIndexed { i, name ->
            db.execSQL(
                "INSERT OR IGNORE INTO item_categories (name, sortOrder) VALUES (?, ?)",
                arrayOf<Any>(name, i + 1)
            )
        }
    }
}

// ── Repository ───────────────────────────────────────────────────────────────

class ItemNameRepository(private val dao: ItemNameDao) {

    /** Id of the alias for [rawName], creating it if needed; null for blank text. */
    suspend fun aliasIdFor(rawName: String): Long? {
        val raw = rawName.trim().takeIf { it.isNotEmpty() } ?: return null
        dao.findAlias(raw)?.let { return it.id }
        val id = dao.insertAlias(ItemAlias(rawName = raw))
        return if (id > 0) id else dao.findAlias(raw)?.id
    }

    /** Common name with this name (case-insensitive), creating it if needed. */
    suspend fun commonNameFor(name: String, categoryName: String? = null): CommonName {
        val trimmed = name.trim()
        val categoryId = categoryName?.let { dao.findCategory(it.trim())?.id }
        val existing = dao.findCommonName(trimmed)
        if (existing != null) {
            // Only fill a missing category; never override the user's choice
            if (existing.categoryId == null && categoryId != null) {
                val updated = existing.copy(categoryId = categoryId)
                dao.updateCommonName(updated)
                return updated
            }
            return existing
        }
        val created = CommonName(name = trimmed, categoryId = categoryId, usageCount = 0)
        return created.copy(id = dao.insertCommonName(created))
    }

    /**
     * Makes [rawName] convert to [commonName] (on all receipts); returns the alias id.
     * A blank [commonName] removes the link.
     */
    suspend fun assign(rawName: String, commonName: String?, categoryName: String? = null): Long? {
        val aliasId = aliasIdFor(rawName) ?: return null
        val target = commonName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { commonNameFor(it, categoryName) }
        dao.setAliasCommonName(aliasId, target?.id)
        return aliasId
    }

    /** Known conversions for these raw texts, keyed by raw text. */
    suspend fun knownNames(rawNames: Collection<String>): Map<String, KnownName> {
        val distinct = rawNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // SQLite limits the number of query parameters, so look up in chunks
        return distinct.chunked(500).flatMap { dao.knownNames(it) }.associateBy { it.rawName }
    }

    /** Bumps the usage count of a name the LLM picked. */
    suspend fun countUsage(commonName: String) {
        dao.findCommonName(commonName.trim())?.let {
            dao.updateCommonName(it.copy(usageCount = it.usageCount + 1))
        }
    }
}
