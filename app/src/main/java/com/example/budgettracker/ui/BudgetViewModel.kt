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
import kotlinx.coroutines.FlowPreview
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

/** One item of a reviewed receipt, with the common name / category chosen in the review. */
data class ReviewedItem(
    val name: String,
    val price: Double,
    val commonName: String? = null,
    val category: String? = null
)

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
            "Error: ${e.message?.take(300)}"
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

    // ── Common item names & categories ────────────────────────────────────────

    private val itemNameDao = db.itemNameDao()
    private val itemNames = ItemNameRepository(itemNameDao)

    val categories: StateFlow<List<ItemCategory>> = itemNameDao.categories()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categoriesWithCounts: Flow<List<CategoryWithCount>> = itemNameDao.categoriesWithCounts()

    /** All common name strings, for autocomplete. */
    val commonNameStrings: StateFlow<List<String>> = itemNameDao.commonNameStrings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Search text on the common names page; matches common names and receipt item texts. */
    val commonNameQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class)
    val commonNameResults: StateFlow<List<CommonNameRow>> = commonNameQuery
        .debounce(200)
        .flatMapLatest { itemNameDao.searchCommonNames(it.trim(), 500) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun commonName(id: Long): Flow<CommonName?> = itemNameDao.commonName(id)
    fun aliasesFor(commonNameId: Long): Flow<List<AliasRow>> = itemNameDao.aliasesFor(commonNameId)
    suspend fun searchAliases(query: String): List<AliasRow> = itemNameDao.searchAliases(query.trim())

    /** Common name and category per receipt item id. */
    fun itemNamesForReceipt(receiptId: Long): Flow<Map<Long, ItemNameInfo>> =
        itemNameDao.itemNamesForReceipt(receiptId).map { list -> list.associateBy { it.itemId } }

    /** Creates a common name; returns its id, or an error if the name exists already. */
    suspend fun createCommonName(name: String, categoryId: Long?): Result<Long> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name can't be empty" }
        require(itemNameDao.findCommonName(trimmed) == null) { "\"$trimmed\" already exists" }
        itemNameDao.insertCommonName(CommonName(name = trimmed, categoryId = categoryId, usageCount = 0))
    }

    /** Renames / re-categorizes; fails if another common name already has [name]. */
    suspend fun updateCommonName(commonName: CommonName, name: String, categoryId: Long?): Result<Unit> =
        runCatching {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "Name can't be empty" }
            val clash = itemNameDao.findCommonName(trimmed)
            require(clash == null || clash.id == commonName.id) {
                "\"$trimmed\" already exists. Select both in the list and merge them instead."
            }
            itemNameDao.updateCommonName(commonName.copy(name = trimmed, categoryId = categoryId))
        }

    /** Deletes common names; their receipt items keep their text but lose the common name. */
    fun deleteCommonNames(ids: List<Long>) = viewModelScope.launch { itemNameDao.deleteCommonNames(ids) }

    /** Merges [ids] into [keepId], renamed to [name] (must not clash with an unmerged name). */
    suspend fun mergeCommonNames(ids: List<Long>, keepId: Long, name: String, categoryId: Long?): Result<Unit> =
        runCatching {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "Name can't be empty" }
            val clash = itemNameDao.findCommonName(trimmed)
            require(clash == null || clash.id in ids) { "\"$trimmed\" already exists" }
            val keep = itemNameDao.commonNamesOnce(listOf(keepId)).single()
            itemNameDao.merge(keep.copy(name = trimmed, categoryId = categoryId), ids)
        }

    /** Makes receipt text [rawName] convert to the common name [commonNameId] (on all receipts). */
    fun linkAlias(rawName: String, commonNameId: Long) = viewModelScope.launch {
        itemNames.aliasIdFor(rawName)?.let { itemNameDao.setAliasCommonName(it, commonNameId) }
    }

    /** Receipt text [aliasId] no longer converts to any common name. */
    fun unlinkAlias(aliasId: Long) = viewModelScope.launch { itemNameDao.setAliasCommonName(aliasId, null) }

    /** Changes what one receipt text converts to (on all receipts); blank removes the link. */
    fun setCommonNameForItem(rawName: String, commonName: String) = viewModelScope.launch {
        itemNames.assign(rawName, commonName)
    }

    suspend fun addCategory(name: String): Result<Unit> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name can't be empty" }
        require(itemNameDao.findCategory(trimmed) == null) { "\"$trimmed\" already exists" }
        itemNameDao.insertCategory(ItemCategory(name = trimmed, sortOrder = itemNameDao.maxCategorySortOrder() + 1))
    }

    suspend fun renameCategory(category: ItemCategory, name: String): Result<Unit> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name can't be empty" }
        val clash = itemNameDao.findCategory(trimmed)
        require(clash == null || clash.id == category.id) { "\"$trimmed\" already exists" }
        itemNameDao.updateCategory(category.copy(name = trimmed))
    }

    /** Deletes a category; its common names become uncategorized. */
    fun deleteCategory(category: ItemCategory) = viewModelScope.launch { itemNameDao.deleteCategory(category) }

    /**
     * Asks the LLM for a common name and category per item; returns 1-based index → suggestion.
     * Throws with a message for the user when there's no API key, the request fails
     * or the answer contains no names.
     */
    private suspend fun callLlmForCommonNames(names: List<String>): Map<Int, NameSuggestion> {
        if (names.isEmpty()) return emptyMap()
        val key = llmApiKey.value
        if (key.isBlank()) error("No LLM API key set. Go to Settings → LLM receipt parsing.")
        val categoryNames = itemNameDao.categoriesOnce().map { it.name }
        // The most used names, so the prompt stays small even with a large name list
        val known = itemNameDao.frequentCommonNames(300)
        val prompt = CommonNamePrompt.build(names, known, categoryNames)

        val response = LlmClient.generate(prompt, key, llmUrl())
        val json = LlmClient.extractJsonFromResponse(response)
            ?: error("The LLM answer contained no JSON: ${response.take(120)}")
        val result = CommonNamePrompt.parse(json, names.size, categoryNames)
        if (result.isEmpty()) error("The LLM returned no names")
        return result
    }

    /**
     * Common names for items still in the review stage (not yet saved); 1-based index →
     * suggestion. Texts with a known conversion are answered from the database; only the
     * rest go to the LLM.
     */
    suspend fun resolveNamesForReview(names: List<String>): Result<Map<Int, NameSuggestion>> =
        runCatching {
            val known = itemNames.knownNames(names)
            val result = mutableMapOf<Int, NameSuggestion>()
            val unknown = mutableListOf<Int>()   // 0-based positions in names
            names.forEachIndexed { i, raw ->
                val k = known[raw.trim()]
                if (k != null) result[i + 1] = NameSuggestion(k.commonName, k.categoryName)
                else unknown += i
            }
            val suggested = callLlmForCommonNames(unknown.map { names[it] })
            suggested.forEach { (j, suggestion) -> result[unknown[j - 1] + 1] = suggestion }
            result
        }

    /** Known common names (from earlier receipts) for review rows; 1-based index → suggestion. */
    suspend fun knownNamesForReview(names: List<String>): Map<Int, NameSuggestion> {
        val known = itemNames.knownNames(names)
        return names.withIndex().mapNotNull { (i, raw) ->
            known[raw.trim()]?.let { i + 1 to NameSuggestion(it.commonName, it.categoryName) }
        }.toMap()
    }

    /**
     * Resolves common names for a saved receipt's items that don't have one yet, via the
     * LLM; the result applies to every receipt with the same item text. Returns how many
     * items got a name.
     */
    suspend fun resolveCommonNamesWithLlm(items: List<ReceiptItem>, names: Map<Long, ItemNameInfo>): Result<Int> =
        runCatching {
            val missing = items.filter { names[it.id]?.commonName == null }
            val rawNames = missing.map { it.name.trim() }.distinct()
            val suggested = callLlmForCommonNames(rawNames)
            suggested.forEach { (i, s) ->
                itemNames.assign(rawNames[i - 1], s.name, s.category)
                itemNames.countUsage(s.name)
            }
            val resolved = suggested.keys.map { rawNames[it - 1] }.toSet()
            missing.count { it.name.trim() in resolved }
        }

    /** Reprocess a stored receipt's Level 0 JSON with any given processor config. */
    fun reprocessReceipt(receipt: Receipt, config: ProcessorConfig): ParsedReceipt {
        val doc = Level0Doc.fromJsonString(receipt.rawJson)
        return ReceiptProcessor.process(doc, config)
    }

    /** Confirm the reviewed receipt: create expense + receipt + items in DB. */
    fun confirmReceiptImport(
        shopName: String?,
        amount: Double,
        items: List<ReviewedItem>,
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
        val receiptItems = items.mapIndexed { i, item ->
            // A name from the review applies to all receipts with this item text
            val aliasId = if (item.commonName.isNullOrBlank()) itemNames.aliasIdFor(item.name)
            else itemNames.assign(item.name, item.commonName, item.category)
                .also { itemNames.countUsage(item.commonName) }
            ReceiptItem(receiptId = receiptId, name = item.name, totalPrice = item.price, sortOrder = i, aliasId = aliasId)
        }
        receiptRepo.replaceItems(receiptId, receiptItems)
        receiptImportState.value = ReceiptImportState.Idle
        refresh()
    }

    fun updateReceiptItem(item: ReceiptItem) = viewModelScope.launch {
        receiptRepo.updateItem(item.copy(aliasId = itemNames.aliasIdFor(item.name)))
    }
    /**
     * Saves an edited receipt item. A non-null [commonName] becomes the common name of the
     * item's text on all receipts (blank removes it); null leaves it unchanged.
     */
    fun saveReceiptItem(item: ReceiptItem, name: String, price: Double, commonName: String?) =
        viewModelScope.launch {
            val raw = name.trim()
            val aliasId = if (commonName == null) itemNames.aliasIdFor(raw) else itemNames.assign(raw, commonName)
            receiptRepo.updateItem(item.copy(name = raw, totalPrice = price, aliasId = aliasId))
        }
    fun deleteReceiptItem(item: ReceiptItem) = viewModelScope.launch { receiptRepo.deleteItem(item) }
    fun addReceiptItem(receiptId: Long, name: String, price: Double) = viewModelScope.launch {
        receiptRepo.insertItem(ReceiptItem(receiptId = receiptId, name = name, totalPrice = price, aliasId = itemNames.aliasIdFor(name)))
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
