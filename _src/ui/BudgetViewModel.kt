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
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setLlmApiKey(key: String) = viewModelScope.launch { settings.setLlmApiKey(key) }
    fun setLlmApiUrl(url: String) = viewModelScope.launch { settings.setLlmApiUrl(url) }

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
        val id = dao.insert(Expense(amount = amount, note = note, timestamp = System.currentTimeMillis(), shopName = shopName?.takeIf { it.isNotBlank() }))
        refresh()
        return id
    }

    fun addExpense(amount: Double, note: String, shopName: String? = null) =
        viewModelScope.launch { addExpenseAndGetId(amount, note, shopName) }

    fun expenseById(id: Long): Flow<Expense?> = dao.getExpenseById(id)

    fun deleteExpense(expense: Expense) = viewModelScope.launch { dao.delete(expense) }

    fun updateExpense(expense: Expense, amount: Double, note: String, shopName: String? = null) =
        viewModelScope.launch {
            dao.update(expense.copy(amount = amount, note = note, shopName = shopName))
            if (!shopName.isNullOrBlank()) {
                val existing = shopRepo.findByName(shopName)
                if (existing == null) shopRepo.insertShop(Shop(name = shopName))
            }
        }

    fun clearMonth(yearMonth: YearMonth) = viewModelScope.launch {
        val zone = ZoneId.systemDefault()
        val start = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = yearMonth.atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        dao.deleteInRange(start, end)
    }

    fun refresh() { refreshTick.value = System.currentTimeMillis() }

    // ── Shops ─────────────────────────────────────────────────────────────────

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
                .addOnSuccessListener { loc -> cont.resume(loc?.let { it.latitude to it.longitude }) {} }
                .addOnFailureListener { cont.resume(null) {} }
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
                val shopName = ReceiptProcessor.extractShopName(doc)

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
                val response = LlmClient.generate(prompt, key, url.ifBlank {
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
                })
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

    /** Reprocess a stored receipt's Level 0 JSON with any given processor config. */
    fun reprocessReceipt(receipt: Receipt, config: ProcessorConfig): ParsedReceipt {
        val doc = Level0Doc.fromJsonString(receipt.rawJson)
        return ReceiptProcessor.process(doc, config)
    }

    /** Confirm the reviewed receipt: create expense + receipt + items in DB. */
    fun confirmReceiptImport(
        shopName: String?,
        amount: Double,
        items: List<Pair<String, Double>>,  // name → price
        doc: Level0Doc,
        parsedReceipt: ParsedReceipt
    ) = viewModelScope.launch {
        val expenseId = addExpenseAndGetId(amount, "Receipt", shopName)
        // Ensure the shop name is persisted to the shops table so it appears in Manage Shops
        if (!shopName.isNullOrBlank()) {
            val existing = shopRepo.findByName(shopName)
            if (existing == null) shopRepo.insertShop(Shop(name = shopName))
        }
        val receipt = Receipt(
            expenseId = expenseId,
            shopName = shopName,
            rawJson = doc.toJsonString(),
            processorId = parsedReceipt.processorId
        )
        val receiptId = receiptRepo.insertReceipt(receipt)
        val receiptItems = items.mapIndexed { i, (name, price) ->
            ReceiptItem(receiptId = receiptId, name = name, totalPrice = price, sortOrder = i)
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
        } catch (e: Exception) {
            onDone(false)
        }
    }
}
