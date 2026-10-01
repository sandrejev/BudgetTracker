package com.example.budgettracker.receipt

import kotlin.math.abs
import kotlin.math.roundToLong

private val PRICE_REGEX = Regex("""^-?\d{1,6}[.,]\d{2}$""")

// VAT tax-class letters (A, B…) are sometimes appended to the price token by OCR,
// e.g. "3,98 B" — strip trailing whitespace + single letter before parsing.
private val VAT_SUFFIX_REGEX = Regex("""\s*[A-Za-z]$""")

// Penny/Rewe mark items excluded from discounts with a trailing "*", e.g. "0,25 A *".
private val STAR_SUFFIX_REGEX = Regex("""\s*\*+$""")

// OCR reads the minus of "-0,59" as various dashes, sometimes followed by a space
private val LEADING_MINUS_REGEX = Regex("""^[-−–—]\s*""")

/** Parse a price string supporting both `.` and `,` as decimal separator.
 *  Tolerates a trailing VAT class letter with or without preceding space and a
 *  trailing "*", e.g. "3,98 B" → 3.98, "2,79a" → 2.79, "0,25 A *" → 0.25. */
fun parsePrice(text: String): Double? {
    val t = text.trim().replace(STAR_SUFFIX_REGEX, "").replace(VAT_SUFFIX_REGEX, "")
        .replace(LEADING_MINUS_REGEX, "-")
    // OCR sometimes reads a zero as the letter O: "O,79"
    val digits = if (PRICE_REGEX.matches(t)) t else t.replace('O', '0').replace('o', '0')
    if (!PRICE_REGEX.matches(digits)) return null
    return digits.replace(',', '.').toDoubleOrNull()
}

// VAT class letter of a price token or the token after it: "0,59 A", "2,79a"
private val VAT_CLASS_REGEX = Regex("""^[A-Da-d]$""")
// First token of a VAT table row: "A", "B", "A="
private val VAT_ROW_REGEX = Regex("""^([A-D])=?$""")

/**
 * One receipt item; identical lines are grouped into one item with a [quantity].
 * @property price what was paid in total, after [discount]
 * @property quantity number of units bought (or the weight, e.g. 0.436 kg); 1 when not printed
 * @property discount total discount for all units (positive amount), already subtracted from [price]
 */
data class ParsedItem(
    val name: String,
    val price: Double,
    val rawLine: String = "",
    val quantity: Double = 1.0,
    val discount: Double? = null
) {
    /** Regular price of one unit, before discount. */
    val unitPrice: Double get() = if (quantity > 0) roundCents((price + (discount ?: 0.0)) / quantity) else price

    /** Discount per unit. */
    val unitDiscount: Double get() = if (quantity > 0) roundCents((discount ?: 0.0) / quantity) else 0.0
}

data class ParsedReceipt(
    val items: List<ParsedItem>,
    val detectedTotal: Double?,
    val processorId: String
)

/** A quantity printed on a receipt and where it is in the line text. */
data class LineQuantity(val amount: Double, val unitPrice: Double, val range: IntRange)

// "2 Stk x 1,99", "0,436 kg x 2,99" — amount first
private val QTY_FIRST_REGEX =
    Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(?:stk\.?|st\.?|kg)?\s*[x×*]\s*(\d+[.,]\d{2})""")
// "0,65 x 2" (Lidl) — unit price first
private val PRICE_FIRST_REGEX = Regex("""(\d+[.,]\d{2})\s*[x×]\s*(\d+)(?![.,]\d)""")
// Words that may surround a quantity without being an item name
private val QTY_FILLER_REGEX = Regex("""(?i)^(eur|€|eur/kg|/kg|kg|stk\.?|st\.?|[a-b]|\*+)$""")

private fun toNumber(text: String) = text.replace(',', '.').toDoubleOrNull()
fun roundCents(value: Double) = (value * 100).roundToLong() / 100.0

/**
 * Merges items with the same name, unit price and discount per unit into one item with
 * the summed quantity, price and discount (in order of first appearance). Two identical
 * items of which only one is discounted stay separate.
 */
fun groupItems(items: List<ParsedItem>): List<ParsedItem> {
    val groups = LinkedHashMap<Triple<String, Double, Double>, ParsedItem>()
    for (item in items) {
        val key = Triple(item.name.trim(), item.unitPrice, item.unitDiscount)
        val existing = groups[key]
        groups[key] = if (existing == null) item else existing.copy(
            price = roundCents(existing.price + item.price),
            quantity = existing.quantity + item.quantity,
            discount = if (existing.discount == null && item.discount == null) null
                else roundCents((existing.discount ?: 0.0) + (item.discount ?: 0.0)),
            rawLine = existing.rawLine + "\n" + item.rawLine
        )
    }
    return groups.values.toList()
}

// "0,65 x" — a unit price followed by a multiplication sign (the count may be missing or misread)
private val UNIT_PRICE_X_REGEX = Regex("""(\d+[.,]\d{2})\s*[x×*]""")

private fun fitsPrice(value: Double, price: Double) = abs(value - price) <= 0.02

/**
 * The quantity of a line that costs [linePrice] in total (before discount). A printed
 * quantity only counts if quantity × unit price matches the price, so a misread
 * "0,65 x 1,30" (OCR missed the "2") isn't taken as 0.65 units. If the count is missing
 * or unreadable, it is derived from the unit price: 1,30 / 0,65 = 2.
 */
fun resolveQuantity(text: String, linePrice: Double): Double? {
    val target = abs(linePrice)
    for (m in QTY_FIRST_REGEX.findAll(text)) {
        val amount = toNumber(m.groupValues[1]) ?: continue
        val unit = toNumber(m.groupValues[2]) ?: continue
        if (amount > 0 && fitsPrice(amount * unit, target)) return amount
    }
    for (m in PRICE_FIRST_REGEX.findAll(text)) {
        val unit = toNumber(m.groupValues[1]) ?: continue
        val amount = toNumber(m.groupValues[2]) ?: continue
        if (amount > 0 && fitsPrice(amount * unit, target)) return amount
    }
    for (m in UNIT_PRICE_X_REGEX.findAll(text)) {
        val unit = toNumber(m.groupValues[1])?.takeIf { it > 0 } ?: continue
        val count = target / unit
        val rounded = Math.round(count).toDouble()
        if (rounded >= 2 && abs(count - rounded) < 0.01) return rounded
    }
    return null
}

/** Finds a quantity like "2 Stk x 1,99" or "0,65 x 2" in [text]. */
fun findQuantity(text: String): LineQuantity? {
    QTY_FIRST_REGEX.find(text)?.let { m ->
        val amount = toNumber(m.groupValues[1]) ?: return@let
        val unit = toNumber(m.groupValues[2]) ?: return@let
        if (amount > 0) return LineQuantity(amount, unit, m.range)
    }
    PRICE_FIRST_REGEX.find(text)?.let { m ->
        val unit = toNumber(m.groupValues[1]) ?: return@let
        val amount = toNumber(m.groupValues[2]) ?: return@let
        if (amount > 0) return LineQuantity(amount, unit, m.range)
    }
    return null
}

/**
 * True when [text] holds nothing but a quantity (plus prices / unit words), like the
 * "2 Stk x 1,99" line Rewe and Penny print below an item.
 */
private fun isQuantityOnlyLine(text: String, quantity: LineQuantity): Boolean =
    text.removeRange(quantity.range)
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() && parsePrice(it) == null && !QTY_FILLER_REGEX.matches(it) }
        .none { word -> word.any { it.isLetter() } }

private val WORD_SPLIT_REGEX = Regex("""[^\p{L}]+""")

private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        prev = cur
    }
    return prev[b.length]
}

/**
 * True when [text] contains one of [keywords]. Single-word keywords of 5+ letters also
 * match a word one OCR error away ("Rabalt", "Rabat" for "Rabatt"), since discount lines
 * are often printed in colour and read less reliably.
 */
fun containsKeyword(text: String, keywords: List<String>): Boolean {
    if (keywords.any { text.contains(it, ignoreCase = true) }) return true
    val words = text.lowercase().split(WORD_SPLIT_REGEX).filter { it.length >= 4 }
    return keywords.any { kw ->
        val k = kw.lowercase()
        k.length >= 5 && ' ' !in k && words.any { abs(it.length - k.length) <= 1 && editDistance(it, k) <= 1 }
    }
}

object ReceiptProcessor {

    fun process(doc: Level0Doc, config: ProcessorConfig): ParsedReceipt {
        val items = mutableListOf<ParsedItem>()
        var total: Double? = null
        var totalFound = false
        // When totalTargetSections is empty, the total is searched in the same sections as items.
        val effectiveTotalSections = config.totalTargetSections.ifEmpty { config.targetSections }

        // Table-based strategy state — reset at each separator within a scanned section
        var tableMode = false
        var nameColX: Float? = null
        var priceColX: Float? = null

        fun matchesAny(text: String, keywords: List<String>) =
            keywords.any { text.contains(it, ignoreCase = true) }

        // VAT class (A, B…) of each item in [items], when printed next to its price
        val vatClasses = mutableListOf<String?>()
        // Items with a discount label whose amount OCR missed, and lines with a name but
        // no readable price; both are filled in from the totals at the end (see reconcile)
        val unknownDiscount = mutableSetOf<Int>()
        val unknownPrice = mutableSetOf<Int>()
        // Gross total per VAT class from the VAT table below the total ("A 7% 0,79 11,35 12,14")
        val vatTotals = mutableMapOf<String, Double>()

        fun addItem(item: ParsedItem, vatClass: String? = null) {
            items += item
            vatClasses += vatClass
        }

        fun applyDiscountAt(index: Int, amount: Double) {
            val item = items[index]
            items[index] = item.copy(
                price = roundCents(item.price - amount),
                discount = roundCents((item.discount ?: 0.0) + amount)
            )
            unknownDiscount -= index
        }

        // Reduces the last item by a discount printed below it ("Lidl Plus Rabatt -0,59").
        fun applyDiscount(amount: Double) {
            // A "name without price" line right above a discount was its unreadable label
            if (items.lastIndex in unknownPrice) {
                unknownPrice -= items.lastIndex
                items.removeAt(items.lastIndex)
                vatClasses.removeAt(vatClasses.lastIndex)
            }
            if (items.isNotEmpty()) applyDiscountAt(items.lastIndex, amount)
        }
        // A discount label whose amount OCR put on the next line
        var discountAmountPending = false

        for (section in doc.sections) {
            val scanForItems = section.type in config.targetSections
            val scanForTotal = section.type in effectiveTotalSections
            if (!scanForItems && !scanForTotal) {
                if (totalFound) section.lines.filterIsInstance<L0Line.Tokens>().forEach { line ->
                    readVatRow(line.tokens)?.let { (c, gross) -> vatTotals.putIfAbsent(c, gross) }
                }
                continue
            }

            for (line in section.lines) {
                if (totalFound && line is L0Line.Tokens) readVatRow(line.tokens)?.let { (c, gross) ->
                    vatTotals.putIfAbsent(c, gross)
                }
                // ── Separator: reset table mode so only the relevant table is parsed ──
                if (line is L0Line.Separator) {
                    if (config.tableHeaderKeywords.isNotEmpty()) {
                        tableMode = false
                        nameColX = null
                        priceColX = null
                    }
                    continue
                }

                val tokens = (line as L0Line.Tokens).tokens
                if (tokens.isEmpty()) continue

                val fullText = tokens.joinToString(" ") { it.text }

                // ── Total line? (only checked in total sections) ──────────────────
                if (scanForTotal && !totalFound) {
                    if (matchesAny(fullText, config.totalKeywords)) {
                        val priceToken = tokens
                            .filter { it.x >= config.totalPriceXMin }
                            .lastOrNull { parsePrice(it.text) != null }
                        priceToken?.let { total = parsePrice(it.text)?.let(::abs) }
                        totalFound = true
                        continue
                    }
                }

                // ── Item detection (only in item sections) ────────────────────────
                if (!scanForItems) continue

                // ── Stop item extraction once the total was found ─────────────────
                // Payment footer lines (Betrag, Autorisierung, etc.) appear after the
                // total line; skipping them prevents false items from being extracted.
                if (totalFound) continue

                // ── Table-based strategy: detect header row ───────────────────────
                // Must run BEFORE excludeKeywords — the header row often contains
                // words like "Nachlass" that would otherwise cause it to be skipped,
                // preventing tableMode from ever activating.
                if (config.tableHeaderKeywords.isNotEmpty() && !tableMode) {
                    val allHeaderKeywordsPresent = config.tableHeaderKeywords.all { kw ->
                        tokens.any { it.text.equals(kw, ignoreCase = true) }
                    }
                    if (allHeaderKeywordsPresent) {
                        nameColX = config.nameColumnKeyword?.let { kw ->
                            tokens.firstOrNull { it.text.equals(kw, ignoreCase = true) }?.x
                        }
                        priceColX = config.priceColumnKeyword?.let { kw ->
                            tokens.firstOrNull { it.text.equals(kw, ignoreCase = true) }?.x
                        }
                        tableMode = true
                        continue  // skip the header row itself
                    }
                }

                // ── Quantity line below an item ("2 Stk x 1,99") ───────────────────
                val quantityLine = findQuantity(fullText)
                if (quantityLine != null && isQuantityOnlyLine(fullText, quantityLine)) {
                    if (items.isNotEmpty()) {
                        val item = items.last()
                        resolveQuantity(fullText, item.price + (item.discount ?: 0.0))?.let {
                            items[items.lastIndex] = item.copy(quantity = it)
                        }
                    }
                    continue
                }

                val isItemDiscount = containsKeyword(fullText, config.discountKeywords)
                val isReceiptDiscount = containsKeyword(fullText, config.receiptDiscountKeywords)
                val amountPending = discountAmountPending
                discountAmountPending = false

                // ── Find price and name with the active strategy ──────────────────
                val priceToken: L0Token
                val nameTokens: List<L0Token>
                if (tableMode && nameColX != null && priceColX != null) {
                    // Table mode: price is the parseable token closest to priceColX
                    priceToken = tokens
                        .filter { parsePrice(it.text) != null }
                        .minByOrNull { abs(it.x - priceColX) }
                        ?: continue
                    // Name tokens: between nameColX (±5% tolerance) and priceColX
                    nameTokens = tokens.filter { tok ->
                        tok !== priceToken && tok.x >= (nameColX - 0.05f) && tok.x < priceColX
                    }
                } else if (!tableMode || config.tableHeaderKeywords.isEmpty()) {
                    // Column mode: price in [priceXMin, priceXMax]; name in [0, nameXMax]
                    // A discount amount may sit slightly outside the price column
                    val columnPrice = tokens
                        .filter { it.x >= config.priceXMin && it.x <= config.priceXMax }
                        .lastOrNull { parsePrice(it.text) != null }
                    val found = columnPrice ?: if (isItemDiscount) {
                        tokens.lastOrNull { it.x > config.nameXMax && parsePrice(it.text) != null }
                    } else null
                    if (found == null) {
                        if (isItemDiscount) {
                            discountAmountPending = true
                            if (items.isNotEmpty()) unknownDiscount += items.lastIndex
                        } else if (items.isNotEmpty() && isNameOnlyLine(tokens, config) &&
                            !isReceiptDiscount && !matchesAny(fullText, config.excludeKeywords)
                        ) {
                            // An item whose price OCR missed ("Biokompost Papierb.")
                            unknownPrice += items.size
                            addItem(ParsedItem(name = fullText, price = 0.0, rawLine = fullText))
                        }
                        continue
                    }
                    priceToken = found
                    // Use object identity (not text equality) so tokens like "0,25" that
                    // appear in the name position aren't wrongly excluded.
                    nameTokens = tokens.filter { it !== priceToken && it.x <= config.nameXMax }
                } else {
                    // tableHeaderKeywords is set but the header wasn't found yet
                    continue
                }
                val price = parsePrice(priceToken.text) ?: continue
                val name = nameTokens.joinToString(" ") { it.text }.trim()

                // ── Discounts ─────────────────────────────────────────────────────
                // A discount for the whole receipt (Müller "Müller Blüten" after the
                // subtotal) becomes its own line with a negative price.
                if (isReceiptDiscount) {
                    addItem(ParsedItem(name = name.ifBlank { "Discount" }, price = -abs(price), rawLine = fullText))
                    continue
                }
                // A discount printed below an item ("Lidl Plus Rabatt -0,59") reduces it.
                if (isItemDiscount) {
                    applyDiscount(abs(price))
                    continue
                }
                // A negative amount without a name right below an item is its discount,
                // with the label on the line before or misread beyond recognition.
                if (name.isBlank() && (price < 0 || amountPending)) {
                    applyDiscount(abs(price))
                    continue
                }

                // ── Exclude keywords? ─────────────────────────────────────────────
                if (matchesAny(fullText, config.excludeKeywords)) continue

                if (name.isBlank()) continue
                // Negative lines that aren't discounts (e.g. bottle deposit returns)
                // keep their sign so the items add up to the total.
                addItem(
                    ParsedItem(
                        name = name,
                        price = price,
                        rawLine = fullText,
                        quantity = resolveQuantity(fullText, price) ?: 1.0
                    ),
                    vatClassOf(tokens, priceToken)
                )
            }
        }

        reconcile(items, vatClasses, unknownDiscount, unknownPrice, vatTotals, total, ::applyDiscountAt)
        val complete = items.filterIndexed { i, _ -> i !in unknownPrice }
        return ParsedReceipt(groupItems(complete), total, config.id)
    }

    /**
     * Fills in what OCR missed from the printed totals: first a missing discount from the
     * VAT table (the items of its VAT class add up to more than the class total), then a
     * single missing discount or item price from the receipt total. Resolved items are
     * removed from [unknownDiscount] / [unknownPrice]; unresolved discounts stay 0.
     */
    private fun reconcile(
        items: MutableList<ParsedItem>,
        vatClasses: List<String?>,
        unknownDiscount: MutableSet<Int>,
        unknownPrice: MutableSet<Int>,
        vatTotals: Map<String, Double>,
        total: Double?,
        applyDiscountAt: (Int, Double) -> Unit
    ) {
        if (unknownDiscount.isEmpty() && unknownPrice.isEmpty()) return
        fun plausibleDiscount(index: Int, amount: Double) = amount > 0.004 && amount <= items[index].price + 0.004

        // Class totals only help when every item's class is known
        val known = items.indices.filter { it !in unknownPrice }
        if (vatTotals.isNotEmpty() && known.all { vatClasses[it] != null }) {
            for (index in unknownDiscount.toList()) {
                val vatClass = vatClasses[index] ?: continue
                val classTotal = vatTotals[vatClass] ?: continue
                if (unknownDiscount.count { vatClasses[it] == vatClass } != 1) continue
                val sum = known.filter { vatClasses[it] == vatClass }.sumOf { items[it].price }
                val amount = roundCents(sum - classTotal)
                if (plausibleDiscount(index, amount)) applyDiscountAt(index, amount)
            }
        }

        if (total == null) return
        val gap = roundCents(total - items.indices.filter { it !in unknownPrice }.sumOf { items[it].price })
        if (unknownDiscount.size == 1 && unknownPrice.isEmpty()) {
            val index = unknownDiscount.first()
            if (plausibleDiscount(index, -gap)) applyDiscountAt(index, -gap)
        } else if (unknownPrice.size == 1 && unknownDiscount.isEmpty() && gap > 0) {
            val index = unknownPrice.first()
            items[index] = items[index].copy(price = gap)
            unknownPrice.clear()
        }
    }

    /** A row of the VAT table: class letter first, gross amount last ("A 7% 0,79 11,35 12,14"). */
    private fun readVatRow(tokens: List<L0Token>): Pair<String, Double>? {
        val vatClass = tokens.firstOrNull()?.text?.let { VAT_ROW_REGEX.matchEntire(it) }?.groupValues?.get(1)
            ?: return null
        val amounts = tokens.mapNotNull { parsePrice(it.text) }
        if (amounts.size < 2) return null
        return vatClass to amounts.last()
    }

    /** The VAT class letter glued to the price token or right after it. */
    private fun vatClassOf(tokens: List<L0Token>, priceToken: L0Token): String? {
        val glued = priceToken.text.trim().replace(Regex("""\s*\*+$"""), "").lastOrNull()
        if (glued != null && glued.isLetter() && glued.uppercaseChar() in 'A'..'D') return glued.uppercase()
        val next = tokens.getOrNull(tokens.indexOfFirst { it === priceToken } + 1)?.text ?: return null
        return next.takeIf { VAT_CLASS_REGEX.matches(it) }?.uppercase()
    }

    /** A line with only words in the name column and no amount, like an item whose price OCR missed. */
    private fun isNameOnlyLine(tokens: List<L0Token>, config: ProcessorConfig): Boolean =
        tokens.all { it.x <= config.nameXMax && parsePrice(it.text) == null } &&
            tokens.any { tok -> tok.text.count { it.isLetter() } >= 3 }

    /**
     * Try to auto-detect which processor config matches by scanning the header
     * section for shop-name patterns.
     */
    fun detectProcessor(
        doc: Level0Doc,
        configs: List<ProcessorConfig>
    ): ProcessorConfig? {
        val headerText = doc.sections
            .filter { it.type == "header" }
            .flatMap { sec -> sec.lines.filterIsInstance<L0Line.Tokens>() }
            .flatMap { it.tokens }
            .joinToString(" ") { it.text }

        return configs.firstOrNull { it.matchesShopName(headerText) }
    }

    /** Extract the shop name from the first meaningful header line. */
    fun extractShopName(doc: Level0Doc): String? = headerLines(doc, maxLines = 1).firstOrNull()

    /**
     * The first [maxLines] non-blank header lines as plain text. The shop name is
     * usually among them, though not always on the first line.
     */
    fun headerLines(doc: Level0Doc, maxLines: Int = 4): List<String> {
        val header = doc.sections.firstOrNull { it.type == "header" } ?: return emptyList()
        return header.lines.filterIsInstance<L0Line.Tokens>()
            .map { line -> line.tokens.joinToString(" ") { it.text } }
            .filter { it.isNotBlank() }
            .take(maxLines)
    }

    /** Generate the LLM prompt to create a new processor config from a Level 0 doc. */
    fun buildLlmPrompt(doc: Level0Doc, shopName: String? = null): String {
        val l0Json = doc.toJsonString()
        return """
You are a receipt parser configuration generator for a German grocery budget app.

Given the Level 0 JSON structure of a receipt below, generate a processor configuration JSON that extracts line items and the total.

${if (shopName != null) "Shop: $shopName\n" else ""}
Level 0 JSON:
$l0Json

Output a SINGLE valid JSON object with this exact schema (no explanations, no markdown fences):
{
  "id": "<short-lowercase-id>",
  "name": "<Display Name>",
  "shopNamePatterns": ["(?i)<pattern>"],
  "targetSections": ["body"],
  "totalTargetSections": [],
  "nameXMax": <float 0.0-1.0>,
  "priceXMin": <float 0.0-1.0>,
  "priceXMax": <float 0.0-1.0>,
  "excludeKeywords": ["<word>"],
  "discountKeywords": ["rabatt", "preisvorteil"],
  "receiptDiscountKeywords": [],
  "totalKeywords": ["<word>"],
  "totalPriceXMin": <float 0.0-1.0>,
  "tableHeaderKeywords": [],
  "nameColumnKeyword": null,
  "priceColumnKeyword": null
}

Rules:
- targetSections: which receipt sections contain item lines — usually ["header", "body"]: the header/body boundary is a guess (first line ending in a bare price), so items often start in "header"
- totalTargetSections: leave [] to search the total in targetSections; set to e.g. ["body"] when items are in "header" but the total keyword appears in "body"
- nameXMax: x threshold below which tokens form the item name (left-aligned column)
- priceXMin/Max: x range where the final price appears (right-aligned column)
- excludeKeywords: lowercase German words for non-item lines (coupons, subtotals, loyalty points). Do NOT exclude deposits ("Pfand"): they are part of the total
- discountKeywords: words of a discount line printed BELOW an item that reduces that item (e.g. "rabatt" for "Lidl Plus Rabatt -0,59")
- receiptDiscountKeywords: words of a discount on the whole receipt, usually after a subtotal (e.g. "blüten" for "Müller Blüten 2,00")
- Quantities ("2 Stk x 1,99" on its own line below an item, or "0,65 x 2" within the item line) are detected automatically
- totalKeywords: German phrases that mark the grand total line (e.g. "gesamt", "zu zahlen")
- tableHeaderKeywords: if the receipt has a table with a header row, list the keywords that identify it (e.g. ["Artikel", "Preis"]); leave [] for column-based mode
- nameColumnKeyword / priceColumnKeyword: which header token marks each column; null in column-based mode
- Prices use comma as decimal separator (e.g. "1,99") and may have a trailing VAT class letter (e.g. "1,99 B") — handled automatically
- Output ONLY the JSON object, nothing else.
""".trimIndent()
    }
}
