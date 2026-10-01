package com.example.budgettracker.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Export / import all app data as a self-contained JSON file.
 *
 * Schema:
 * {
 *   "version": 1,
 *   "exported": "2025-01-01 12:00",
 *   "expenses": [ { id, amount, note, timestamp, shopName, receipt? } ],
 *   "shops": [ { id, name, logoUri, locations: [...] } ],
 *   "itemCategories": [ { name, sortOrder } ],
 *   "commonNames": [ { name, category, usageCount } ],
 *   "itemAliases": [ { rawName, commonName } ]
 * }
 *
 * Each expense with a receipt embeds the receipt inline:
 * {
 *   "receipt": { rawJson, processorId, items: [ { name, totalPrice, qty, discount } ] }
 * }
 */
object ExportImport {

    suspend fun export(context: Context, db: AppDatabase): String = withContext(Dispatchers.IO) {
        val expenses = db.expenseDao().getAllOnce()
        val receiptsWithItems = db.receiptDao().getAllReceiptsWithItemsOnce()
        val shopsWithLocations = db.shopDao().getAllShopsWithLocationsOnce()

        val receiptMap = receiptsWithItems.associateBy { it.receipt.expenseId }

        val expArr = JSONArray()
        for (exp in expenses) {
            val obj = JSONObject().apply {
                put("id", exp.id)
                put("amount", exp.amount)
                put("note", exp.note)
                put("timestamp", exp.timestamp)
                put("shopName", exp.shopName ?: JSONObject.NULL)
            }
            receiptMap[exp.id]?.let { rw ->
                val recObj = JSONObject().apply {
                    put("rawJson", rw.receipt.rawJson)
                    put("processorId", rw.receipt.processorId ?: JSONObject.NULL)
                    val itemArr = JSONArray()
                    rw.items.sortedBy { it.sortOrder }.forEach { item ->
                        itemArr.put(JSONObject().apply {
                            put("name", item.name)
                            put("totalPrice", item.totalPrice)
                            put("qty", item.qty ?: JSONObject.NULL)
                            put("discount", item.discount ?: JSONObject.NULL)
                        })
                    }
                    put("items", itemArr)
                }
                obj.put("receipt", recObj)
            }
            expArr.put(obj)
        }

        val shopArr = JSONArray()
        for (sw in shopsWithLocations) {
            shopArr.put(JSONObject().apply {
                put("id", sw.shop.id)
                put("name", sw.shop.name)
                put("logoUri", sw.shop.logoUri ?: JSONObject.NULL)
                val locArr = JSONArray()
                sw.locations.forEach { loc ->
                    locArr.put(JSONObject().apply {
                        put("latitude", loc.latitude)
                        put("longitude", loc.longitude)
                        put("label", loc.label)
                    })
                }
                put("locations", locArr)
            })
        }

        // Common item names: categories, names and which receipt texts convert to them
        val nameDao = db.itemNameDao()
        val categories = nameDao.categoriesOnce()
        val categoryById = categories.associate { it.id to it.name }
        val commonNames = nameDao.commonNamesAllOnce()
        val commonNameById = commonNames.associate { it.id to it.name }
        val categoryArr = JSONArray()
        categories.forEach { c ->
            categoryArr.put(JSONObject().put("name", c.name).put("sortOrder", c.sortOrder))
        }
        val commonNameArr = JSONArray()
        commonNames.forEach { cn ->
            commonNameArr.put(JSONObject().apply {
                put("name", cn.name)
                put("category", cn.categoryId?.let { categoryById[it] } ?: JSONObject.NULL)
                put("usageCount", cn.usageCount)
            })
        }
        val aliasArr = JSONArray()
        nameDao.aliasesOnce().forEach { a ->
            aliasArr.put(JSONObject().apply {
                put("rawName", a.rawName)
                put("commonName", a.commonNameId?.let { commonNameById[it] } ?: JSONObject.NULL)
            })
        }

        val now = DATE_FMT.format(Instant.now().atZone(ZoneId.systemDefault()))
        JSONObject().apply {
            put("version", 1)
            put("exported", now)
            put("expenses", expArr)
            put("shops", shopArr)
            put("itemCategories", categoryArr)
            put("commonNames", commonNameArr)
            put("itemAliases", aliasArr)
        }.toString(2)
    }

    suspend fun import(context: Context, db: AppDatabase, jsonString: String) = withContext(Dispatchers.IO) {
        val root = JSONObject(jsonString)
        val expArr = root.optJSONArray("expenses") ?: JSONArray()
        val shopArr = root.optJSONArray("shops") ?: JSONArray()

        // Import shops first (so we can reference IDs later if needed)
        val expDao = db.expenseDao()
        val receiptDao = db.receiptDao()
        val shopDao = db.shopDao()

        // Map old shop IDs to new IDs
        val shopIdMap = mutableMapOf<Long, Long>()
        for (i in 0 until shopArr.length()) {
            val sObj = shopArr.getJSONObject(i)
            val oldId = sObj.getLong("id")
            val shop = Shop(
                name = sObj.getString("name"),
                logoUri = sObj.optString("logoUri").takeIf { it != "null" && it.isNotEmpty() }
            )
            val newId = shopDao.insert(shop)
            shopIdMap[oldId] = newId

            val locArr = sObj.optJSONArray("locations") ?: JSONArray()
            for (j in 0 until locArr.length()) {
                val lObj = locArr.getJSONObject(j)
                shopDao.insertLocation(ShopLocation(
                    shopId = newId,
                    latitude = lObj.getDouble("latitude"),
                    longitude = lObj.getDouble("longitude"),
                    label = lObj.optString("label", "")
                ))
            }
        }

        // Common item names (merged with existing ones by name)
        val nameDao = db.itemNameDao()
        val names = ItemNameRepository(nameDao)
        val categoryArr = root.optJSONArray("itemCategories") ?: JSONArray()
        for (i in 0 until categoryArr.length()) {
            val cObj = categoryArr.getJSONObject(i)
            val name = cObj.getString("name").trim()
            if (name.isNotEmpty() && nameDao.findCategory(name) == null) {
                nameDao.insertCategory(ItemCategory(name = name, sortOrder = cObj.optInt("sortOrder", nameDao.maxCategorySortOrder() + 1)))
            }
        }
        val commonNameArr = root.optJSONArray("commonNames") ?: JSONArray()
        for (i in 0 until commonNameArr.length()) {
            val cnObj = commonNameArr.getJSONObject(i)
            val name = cnObj.getString("name").trim()
            if (name.isEmpty()) continue
            val category = cnObj.optString("category").takeIf { it != "null" && it.isNotEmpty() }
            names.commonNameFor(name, category)
        }
        val aliasArr = root.optJSONArray("itemAliases") ?: JSONArray()
        for (i in 0 until aliasArr.length()) {
            val aObj = aliasArr.getJSONObject(i)
            val commonName = aObj.optString("commonName").takeIf { it != "null" && it.isNotEmpty() }
            if (commonName != null) names.assign(aObj.getString("rawName"), commonName)
            else names.aliasIdFor(aObj.getString("rawName"))
        }

        // Import expenses + receipts
        for (i in 0 until expArr.length()) {
            val eObj = expArr.getJSONObject(i)
            val expense = Expense(
                amount = eObj.getDouble("amount"),
                note = eObj.getString("note"),
                timestamp = eObj.getLong("timestamp"),
                shopName = eObj.optString("shopName").takeIf { it != "null" && it.isNotEmpty() }
            )
            val newExpId = expDao.insert(expense)

            if (eObj.has("receipt")) {
                val rObj = eObj.getJSONObject("receipt")
                val receipt = Receipt(
                    expenseId = newExpId,
                    rawJson = rObj.getString("rawJson"),
                    processorId = rObj.optString("processorId").takeIf { it != "null" && it.isNotEmpty() }
                )
                val newRecId = receiptDao.insertReceipt(receipt)
                val itemArr = rObj.optJSONArray("items") ?: JSONArray()
                val items = (0 until itemArr.length()).map { j ->
                    val iObj = itemArr.getJSONObject(j)
                    val name = iObj.getString("name")
                    ReceiptItem(
                        receiptId = newRecId,
                        name = name,
                        aliasId = names.aliasIdFor(name),
                        totalPrice = iObj.getDouble("totalPrice"),
                        qty = iObj.optString("qty").takeIf { it != "null" && it.isNotEmpty() },
                        discount = iObj.optDouble("discount").takeUnless { it.isNaN() },
                        sortOrder = j
                    )
                }
                receiptDao.insertItems(items)
            }
        }
    }

    suspend fun writeToUri(context: Context, uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
    }

    suspend fun readFromUri(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: error("Cannot read from $uri")
}
