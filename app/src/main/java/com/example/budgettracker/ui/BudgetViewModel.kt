package com.example.budgettracker.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.budgettracker.data.*
import com.example.budgettracker.receipt.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class MonthStats(
    val yearMonth: YearMonth,
    val dailyRate: Double,
    val allowance: Double,
    val spent: Double,
    val balance: Double,
    val entries: List<Expense>,
    val isCurrent: Boolean
)

/** State for an in-progress receipt parse. */
sealed class ReceiptImportState {
    object Idle : ReceiptImportState()
    object Parsing : ReceiptImportState()
    data class NeedsProcessor(
        val doc: Level0Doc,
        val detectedShop: String?,
        val availableProcessors: List<ProcessorConfig>,
        val preselectedProcessor: ProcessorConfig? = null
    ) : ReceiptImportState()
    data class Review(
        val doc: Level0Doc,
        val parsed: ParsedReceipt,
        val processorName: String,
        val detectedShop: String?
    ) : ReceiptImportState()
    data class LlmGenerating(val doc: Level0Doc, val prompt: String) : ReceiptImportState()
    data class Error(val message: String) : ReceiptImportState()
}

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val dao = db.expenseDao()
    private val shopRepo = ShopRepository(db.shopDao())
    private val receiptRepo = ReceiptRepository(db.receiptDao())
    private val processorRepo = ProcessorRepository(app)
    private val settings = SettingsRepository(app)
    private val refreshTick = MutableStateFlow(0L)
    private val fusedLocation = LocationServices.getFusedLocationProviderClient(app)

    // ── Settings ──────────────────────────────────────────────────────────────

    val monthlyLimit: StateFlow<Double> = settings.monthlyLimitFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 600.0)

    fun setMonthlyLimit(limit: Double) = viewModelScope.launch { settings.setMonthlyLimit(limit) }

    val llmApiKey: StateFlow<String> = settings.llmApiKeyFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val llmApiUrl: StateFlow<String> = settings.llmApiUrlFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, LlmClient.DEFAULT_GEMINI_URL)

    fun setLlmApiKey(key: String) = viewModelScope.launch { settings.setLlmApiKey(key) }
    fun setLlmApiUrl(url: String) = viewModelScope.launch { settings.setLlmApiUrl(url) }

    private fun llmUrl() = llmApiUrl.value.ifBlank { LlmClient.DEFAULT_GEMINI_URL }

    val mapStyle: StateFlow<MapStyle> = settings.mapStyleFlow
        .map { MapStyle.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MapStyle.default)

    fun setMapStyle(style: MapStyle) = viewModelScope.launch { settings.setMapStyle(style.name) }

    /** Test the LLM connection. Returns "Connected ✓" on success or an error description. */
    suspend fun testLlmConnection(): String {
        val key = llmApiKey.value
        val url = llmUrl()
        if (key.isBlank()) return "No API key set"
        return try {
            val response = LlmClient.generate("Reply with exactly the word: OK", key, url)
            if (response.isNotBlank()) "Connected ✓" else "Empty response from API"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ── Month stats ───────────────────────────────────────────────────────────

    private fun computeStats(yearMonth: YearMonth, expenses: List<Expense>, limit: Double): MonthStats {
        val isCurrent = yearMonth == YearMonth.now()
        val daysInMonth = yearMonth.lengthOfMonth().toDouble()
        val dailyRate = limit / daysInMonth
        val daysElapsed = if (isCurrent) LocalDate.now().dayOfMonth else daysInMonth.toInt()
        val allowance = daysElapsed * dailyRate
        val spent = expenses.sumOf { it.amount }
        return MonthStats(yearMonth, dailyRate, allowance, spent, allowance - spent, expenses, isCurrent)
    }

    fun statsForMonth(yearMonth: YearMonth): Flow<MonthStats> {
        val zone = ZoneId.systemDefault()
        val startMillis = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = yearMonth.atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        return combine(dao.getExpensesInRange(startMillis, endMillis), settings.monthlyLimitFlow) { expenses, limit ->
            computeStats(yearMonth, expenses, limit)
        }
    }

    val currentMonthStats: StateFlow<MonthStats?> = refreshTick
        .flatMapLatest {
            val ym = YearMonth.now()
            val zone = ZoneId.systemDefault()
            val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = ym.atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
            combine(dao.getExpensesInRange(start, end), settings.monthlyLimitFlow) { expenses, limit ->
                computeStats(ym, expenses, limit)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun allMonthStats(): Flow<List<MonthStats>> =
        combine(dao.getAll(), settings.monthlyLimitFlow) { all, limit ->
            val zone = ZoneId.systemDefault()
            val months = all.map { e ->
                val date = Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate()
                YearMonth.of(date.year, date.month)
            }.distinct().sortedDescending()
            months.map { ym ->
                val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = ym.atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
                computeStats(ym, all.filter { it.timestamp in start..end }, limit)
            }
        }

    // ── Expenses ──────────────────────────────────────────────────────────────

    /** Returns new expense id. */
    suspend fun addExpenseAndGetId(amount: Double, note: String, shopName: String? = null): Long {
        val shop = shopRepo.resolveOrCreate(shopName)
        val id = dao.insert(Expense(amount = amount, note = note, timestamp = System.currentTimeMillis(), shopName = shop))
        refresh()
        return id
    }

    fun addExpense(amount: Double, note: String, shopName: String? = null) =
        viewModelScope.launch { addExpenseAndGetId(amount, note, shopName) }

    fun expenseById(id: Long): Flow<Expense?> = dao.getExpenseById(id)

    fun deleteExpense(expense: Expense) = viewModelScope.launch { dao.delete(expense) }

    fun updateExpense(expense: Expense, amount: Double, note: String, shopName: String? = null) =
        viewModelScope.launch {
            val shop = shopRepo.resolveOrCreate(shopName)
            dao.update(expense.copy(amount = amount, note = note, shopName = shop))
        }

    fun clearMonth(yearMonth: YearMonth) = viewModelScope.launch {
        val zone = ZoneId.systemDefault()
        val start = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = yearMonth.atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        dao.deleteInRange(start, end)
    }

    fun refresh() { refreshTick.value = System.currentTimeMillis() }

    // ── Shops ─────────────────────────────────────────────────────────────────

    /** All saved shops, sorted by name — used for shop-name suggestions. */
    val shops: StateFlow<List<Shop>> =
        shopRepo.allShops().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val shopsWithLocations: StateFlow<List<ShopWithLocations>> =
        shopRepo.allShopsWithLocations()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addShop(name: String, logoUri: String?) = viewModelScope.launch {
        shopRepo.insertShop(Shop(name = name, logoUri = logoUri?.takeIf { it.isNotBlank() }))
    }

    fun updateShop(shop: Shop) = viewModelScope.launch { shopRepo.updateShop(shop) }
    fun deleteShop(shop: Shop) = viewModelScope.launch { shopRepo.deleteShop(shop) }

    fun addShopLocation(shopId: Long, lat: Double, lng: Double, label: String) = viewModelScope.launch {
        shopRepo.insertLocation(ShopLocation(shopId = shopId, latitude = lat, longitude = lng, label = label))
    }

    fun updateShopLocation(location: ShopLocation) = viewModelScope.launch { shopRepo.updateLocation(location) }
    fun deleteShopLocation(location: ShopLocation) = viewModelScope.launch { shopRepo.deleteLocation(location) }

    @SuppressLint("MissingPermission")
    suspend fun detectNearbyShopName(): String? {
        val location = runCatching { getLastLocation() }.getOrNull() ?: return null
        return shopRepo.findNearbyShop(location.first, location.second)?.name
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            fusedLocation.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc -> cont.resume(loc?.let { it.latitude to it.longitude }) }
                .addOnFailureListener { cont.resume(null) }
        }

    // ── Receipts ──────────────────────────────────────────────────────────────

    val receiptImportState = MutableStateFlow<ReceiptImportState>(ReceiptImportState.Idle)

    fun receiptWithItemsByExpense(expenseId: Long): Flow<ReceiptWithItems?> =
        receiptRepo.receiptWithItemsByExpense(expenseId)

    /**
     * Start parsing a shared receipt URI (image or PDF).
     * Updates [receiptImportState] as the pipeline progresses.
     */
    fun startReceiptImport(uri: Uri, mimeType: String) {
        viewModelScope.launch {
            receiptImportState.value = ReceiptImportState.Parsing
            try {
                val ctx = getApplication<Application>()
                val doc = when {
                    mimeType.startsWith("image/") -> Level0Builder.fromImageUri(ctx, uri)
                    mimeType == "application/pdf" -> PdfTextExtractor.toLevel0(ctx, uri)
                    else -> error("Unsupported MIME type: $mimeType")
                }
                val processors = processorRepo.loadAll()
                val detected = ReceiptProcessor.detectProcessor(doc, processors)
                val shopName = matchKnownShop(doc, detected) ?: ReceiptProcessor.extractShopName(doc)

                // Always show the processor selector so the user can confirm or change
                // the auto-detected processor; preselect it when one was found.
                receiptImportState.value = ReceiptImportState.NeedsProcessor(
                    doc = doc,
                    detectedShop = shopName,
                    availableProcessors = processors,
                    preselectedProcessor = detected
                )
            } catch (e: Exception) {
                receiptImportState.value = ReceiptImportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Fuzzy-matches the receipt header against saved shops, so OCR errors like
     * "3Müller" or "LGDL" become "Müller" / "LIDL". Falls back to the name of the
     * auto-detected processor's shop; null if neither applies.
     */
    private suspend fun matchKnownShop(doc: Level0Doc, detected: ProcessorConfig?): String? {
        val known = shopRepo.allShopsOnce().map { it.name }
        return ShopNameMatcher.bestMatch(ReceiptProcessor.headerLines(doc), known)
            ?: detected?.name
    }

    fun applyProcessor(doc: Level0Doc, config: ProcessorConfig, shopName: String?) {
        val parsed = ReceiptProcessor.process(doc, config)
        receiptImportState.value = ReceiptImportState.Review(doc, parsed, config.name, shopName)
    }

    fun generateProcessorWithLlm(doc: Level0Doc, shopName: String?) {
        viewModelScope.launch {
            val prompt = ReceiptProcessor.buildLlmPrompt(doc, shopName)
            receiptImportState.value = ReceiptImportState.LlmGenerating(doc, prompt)
            try {
                val key = llmApiKey.value
                val url = llmApiUrl.value.ifBlank { "" }
                if (key.isBlank()) {
                    receiptImportState.value = ReceiptImportState.Error("No LLM API key set. Go to Settings → LLM API Key.")
                    return@launch
                }
                val response = LlmClient.generate(prompt, key, url.ifBlank { llmUrl() })
                val json = LlmClient.extractJsonFromResponse(response)
                    ?: error("LLM returned no JSON")
                val config = ProcessorConfig.fromJsonString(json)
                processorRepo.save(config)
                val parsed = ReceiptProcessor.process(doc, config)
                receiptImportState.value = ReceiptImportState.Review(doc, parsed, config.name, shopName)
            } catch (e: Exception) {
                receiptImportState.value = ReceiptImportState.Error("LLM error: ${e.message}")
            }
        }
    }

    /**
     * Asks the LLM to assign a short category label to each item name.
     * Returns a list of the same length as [itemNames]; entries are null when
     * the API key is absent or categorization fails.
     */
    suspend fun categorizeItemsWithLlm(itemNames: List<String>): List<String?> {
        val key = llmApiKey.value
        if (key.isBlank() || itemNames.isEmpty()) return List(itemNames.size) { null }
        return try {
            val url = llmUrl()
            val numbered = itemNames.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")
            val prompt = """
You are a grocery receipt categorizer.
Given the following numbered list of receipt line-item names, return ONLY a JSON array of short category strings (one per item, same order).
Use concise English category labels such as: Dairy, Meat, Fish, Bakery, Produce, Frozen, Snacks, Beverages, Alcohol, Household, Personal Care, Other.
Do not add any explanation — output only the JSON array.

Items:
$numbered
""".trimIndent()
            val response = LlmClient.generate(prompt, key, url)
            // Extract JSON array from response
            val text = response.trim()
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start < 0 || end <= start) return List(itemNames.size) { null }
            val jsonArray = org.json.JSONArray(text.substring(start, end + 1))
            List(itemNames.size) { i ->
                if (i < jsonArray.length()) jsonArray.optString(i).takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            List(itemNames.size) { null }
        }
    }

    /**
     * Core LLM call: maps a list of raw names → common names (by 1-based index).
     * Also persists new / increments existing entries in [common_names].
     */
    private suspend fun callLlmForCommonNames(names: List<String>): Map<Int, String> {
        val key = llmApiKey.value
        if (key.isBlank() || names.isEmpty()) return emptyMap()
        val commonNameDao = db.commonNameDao()
        val existing = commonNameDao.getAll().map { it.name }
        val existingList = if (existing.isNotEmpty()) existing.joinToString(", ") else "none yet"
        val numbered = names.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")

        val prompt = """
You are a receipt item name normalizer.
Known common names already in the database: $existingList

For each numbered receipt item below, assign a short human-readable common name in English.
PREFER to reuse a name from the known list when it clearly fits.
When none fit, propose a concise new name (2–4 words, lowercase, e.g. "whole milk", "sourdough bread", "chicken breast").

Return ONLY a JSON object mapping each item number (as a string key) to its common name.
Example: {"1": "whole milk", "2": "sourdough bread", "3": "orange juice"}

Items:
$numbered
""".trimIndent()

        val response = LlmClient.generate(prompt, key, llmUrl())
        val json = LlmClient.extractJsonFromResponse(response) ?: return emptyMap()
        val jsonObj = org.json.JSONObject(json)

        val result = mutableMapOf<Int, String>()
        names.forEachIndexed { i, _ ->
            val commonName = jsonObj.optString("${i + 1}").takeIf { it.isNotBlank() } ?: return@forEachIndexed
            result[i + 1] = commonName
            val entry = commonNameDao.findByName(commonName)
            if (entry != null) commonNameDao.update(entry.copy(usageCount = entry.usageCount + 1))
            else commonNameDao.insert(CommonName(name = commonName))
        }
        return result
    }

    /**
     * Resolves common names for items that are still in the review stage (not yet in DB).
     * Returns a map of 1-based index → common name for the caller to apply to local state.
     */
    suspend fun resolveNamesForReview(names: List<String>): Map<Int, String> =
        try { callLlmForCommonNames(names) } catch (_: Exception) { emptyMap() }

    /**
     * Uses the LLM to assign a short common name to each receipt item.
     * - Fetches all known common names from the DB and offers them to the LLM first.
     * - The LLM reuses an existing name if it fits, otherwise proposes a new one.
     * - New names are inserted into [common_names]; existing ones get their usageCount bumped.
     * - Each item's [ReceiptItem.category] column is updated with the resolved name.
     * Returns a map of itemId → resolved common name.
     */
    suspend fun resolveCommonNamesWithLlm(items: List<ReceiptItem>): Map<Long, String> {
        val key = llmApiKey.value
        if (key.isBlank() || items.isEmpty()) return emptyMap()
        val url = llmUrl()
        val commonNameDao = db.commonNameDao()
        val receiptDao = db.receiptDao()

        val existing = commonNameDao.getAll().map { it.name }
        val existingList = if (existing.isNotEmpty()) existing.joinToString(", ") else "none yet"
        val numbered = items.mapIndexed { i, item -> "${i + 1}. ${item.name}" }.joinToString("\n")

        val prompt = """
You are a receipt item name normalizer.
Known common names already in the database: $existingList

For each numbered receipt item below, assign a short human-readable common name in English.
PREFER to reuse a name from the known list when it clearly fits.
When none fit, propose a concise new name (2–4 words, lowercase, e.g. "whole milk", "sourdough bread", "chicken breast").

Return ONLY a JSON object mapping each item number (as a string key) to its common name.
Example: {"1": "whole milk", "2": "sourdough bread", "3": "orange juice"}

Items:
$numbered
""".trimIndent()

        return try {
            val response = LlmClient.generate(prompt, key, url)
            val json = LlmClient.extractJsonFromResponse(response) ?: return emptyMap()
            val jsonObj = org.json.JSONObject(json)

            val result = mutableMapOf<Long, String>()
            items.forEachIndexed { i, item ->
                val commonName = jsonObj.optString("${i + 1}").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                result[item.id] = commonName

                // Persist to common_names table
                val existingEntry = commonNameDao.findByName(commonName)
                if (existingEntry != null) {
                    commonNameDao.update(existingEntry.copy(usageCount = existingEntry.usageCount + 1))
                } else {
                    commonNameDao.insert(CommonName(name = commonName))
                }

                // Write back to the receipt item's category field
                receiptDao.updateItem(item.copy(category = commonName))
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Reprocess a stored receipt's Level 0 JSON with any given processor config. */
    fun reprocessReceipt(receipt: Receipt, config: ProcessorConfig): ParsedReceipt {
        val doc = Level0Doc.fromJsonString(receipt.rawJson)
        return ReceiptProcessor.process(doc, config)
    }

    /** Confirm the reviewed receipt: create expense + receipt + items in DB.
     *  [items] is a list of Triple(name, price, commonName?) — commonName goes into [ReceiptItem.category]. */
    fun confirmReceiptImport(
        shopName: String?,
        amount: Double,
        items: List<Triple<String, Double, String?>>,
        doc: Level0Doc,
        parsedReceipt: ParsedReceipt
    ) = viewModelScope.launch {
        // Also saves the shop to the shops table so it appears in Manage Shops
        val shop = shopRepo.resolveOrCreate(shopName)
        val expenseId = addExpenseAndGetId(amount, "Receipt", shop)
        val receipt = Receipt(
            expenseId = expenseId,
            shopName = shop,
            rawJson = doc.toJsonString(),
            processorId = parsedReceipt.processorId
        )
        val receiptId = receiptRepo.insertReceipt(receipt)
        val receiptItems = items.mapIndexed { i, (name, price, commonName) ->
            ReceiptItem(receiptId = receiptId, name = name, totalPrice = price, sortOrder = i, category = commonName)
        }
        receiptRepo.replaceItems(receiptId, receiptItems)
        receiptImportState.value = ReceiptImportState.Idle
        refresh()
    }

    fun updateReceiptItem(item: ReceiptItem) = viewModelScope.launch { receiptRepo.updateItem(item) }
    fun deleteReceiptItem(item: ReceiptItem) = viewModelScope.launch { receiptRepo.deleteItem(item) }
    fun addReceiptItem(receiptId: Long, name: String, price: Double) = viewModelScope.launch {
        receiptRepo.insertItem(ReceiptItem(receiptId = receiptId, name = name, totalPrice = price))
    }

    fun resetReceiptImport() { receiptImportState.value = ReceiptImportState.Idle }

    // ── Processors ────────────────────────────────────────────────────────────

    fun loadProcessors(): List<ProcessorConfig> = processorRepo.loadAll()

    fun saveProcessor(config: ProcessorConfig) { processorRepo.save(config) }

    fun deleteProcessor(config: ProcessorConfig) { processorRepo.delete(config) }

    fun importProcessorFromJson(json: String): Result<ProcessorConfig> =
        processorRepo.importFromJson(json)

    fun copyToClipboard(text: String, label: String = "Receipt Data") {
        val ctx = getApplication<Application>()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Build the LLM prompt for a Level 0 doc and copy it to the clipboard. */
    fun copyLlmPrompt(doc: Level0Doc, shopName: String?) {
        val prompt = ReceiptProcessor.buildLlmPrompt(doc, shopName)
        copyToClipboard(prompt, "LLM Prompt")
    }

    /** Copy the Level 0 JSON of a receipt doc to the clipboard. */
    fun copyLevel0Json(doc: Level0Doc) {
        copyToClipboard(doc.toJsonString(indent = 2), "Level 0 JSON")
    }

    // ── Export / Import ────────────────────────────────────────────────────────

    fun exportData(onResult: (String) -> Unit) = viewModelScope.launch {
        val json = ExportImport.export(getApplication(), db)
        onResult(json)
    }

    fun importData(jsonString: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            ExportImport.import(getApplication(), db, jsonString)
            refresh()
            onDone(true)
        } catch (_: Exception) {
            onDone(false)
        }
    }
}
