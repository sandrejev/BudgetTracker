package com.example.budgettracker.receipt

import kotlin.math.abs

private val PRICE_REGEX = Regex("""^-?\d{1,6}[.,]\d{2}$""")

// VAT tax-class letters (A, B…) are sometimes appended to the price token by OCR,
// e.g. "3,98 B" — strip trailing whitespace + single letter before parsing.
private val VAT_SUFFIX_REGEX = Regex("""\s*[A-Za-z]$""")

/** Parse a price string supporting both `.` and `,` as decimal separator.
 *  Tolerates a trailing VAT class letter with or without preceding space,
 *  e.g. "3,98 B" → 3.98, "2,79a" → 2.79. */
fun parsePrice(text: String): Double? {
    val t = text.trim().replace(VAT_SUFFIX_REGEX, "")
    if (!PRICE_REGEX.matches(t)) return null
    return t.replace(',', '.').toDoubleOrNull()
}

data class ParsedItem(
    val name: String,
    val price: Double,
    val rawLine: String = ""
)

data class ParsedReceipt(
    val items: List<ParsedItem>,
    val detectedTotal: Double?,
    val processorId: String
)

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

        for (section in doc.sections) {
            val scanForItems = section.type in config.targetSections
            val scanForTotal = section.type in effectiveTotalSections
            if (!scanForItems && !scanForTotal) continue

            for (line in section.lines) {
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
                if (scanForTotal) {
                    val isTotalLine = config.totalKeywords.any { kw ->
                        fullText.contains(kw, ignoreCase = true)
                    }
                    if (isTotalLine) {
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

                // ── Stop item extraction once total found in a total-scanning section ──
                // Payment footer lines (Betrag, Autorisierung, etc.) appear after the
                // total line; skipping them prevents false items from being extracted.
                if (totalFound && scanForTotal) continue

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

                // ── Exclude keywords? ─────────────────────────────────────────────
                if (config.excludeKeywords.any { kw ->
                        fullText.contains(kw, ignoreCase = true)
                    }) continue

                // ── Extract item using the active strategy ────────────────────────
                if (tableMode && nameColX != null && priceColX != null) {
                    // Table mode: price is the parseable token closest to priceColX
                    val priceToken = tokens
                        .filter { parsePrice(it.text) != null }
                        .minByOrNull { abs(it.x - priceColX!!) }
                        ?: continue

                    val price = parsePrice(priceToken.text) ?: continue

                    // Name tokens: between nameColX (±5% tolerance) and priceColX
                    val nameTokens = tokens
                        .filter { tok ->
                            tok !== priceToken &&
                            tok.x >= (nameColX!! - 0.05f) &&
                            tok.x < priceColX!!
                        }
                        .map { it.text }
                    val name = nameTokens.joinToString(" ").trim()
                    if (name.isBlank()) continue

                    items += ParsedItem(name = name, price = abs(price), rawLine = fullText)

                } else if (!tableMode || config.tableHeaderKeywords.isEmpty()) {
                    // Column mode: price in [priceXMin, priceXMax]; name in [0, nameXMax]
                    val priceToken = tokens
                        .filter { it.x >= config.priceXMin && it.x <= config.priceXMax }
                        .lastOrNull { parsePrice(it.text) != null }
                        ?: continue

                    val price = parsePrice(priceToken.text) ?: continue

                    // Use object identity (not text equality) so tokens like "0,25" that
                    // appear in the name position aren't wrongly excluded.
                    val nameTokens = tokens
                        .filter { it !== priceToken && it.x <= config.nameXMax }
                        .map { it.text }
                    val name = nameTokens.joinToString(" ").trim()
                    if (name.isBlank()) continue

                    items += ParsedItem(name = name, price = abs(price), rawLine = fullText)
                }
                // If tableHeaderKeywords is set but header not yet found, skip this line
            }
        }

        return ParsedReceipt(items, total, config.id)
    }

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
    fun extractShopName(doc: Level0Doc): String? {
        val header = doc.sections.firstOrNull { it.type == "header" } ?: return null
        val firstLine = header.lines.filterIsInstance<L0Line.Tokens>().firstOrNull() ?: return null
        return firstLine.tokens.joinToString(" ") { it.text }.takeIf { it.isNotBlank() }
    }

    /** Generate the LLM prompt to create a new processor config from a Level 0 doc. */
    fun buildLlmPrompt(doc: Level0Doc, shopName: String? = null): String {
        val l0Json = doc.toJsonString(indent = 2)
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
  "totalKeywords": ["<word>"],
  "totalPriceXMin": <float 0.0-1.0>,
  "tableHeaderKeywords": [],
  "nameColumnKeyword": null,
  "priceColumnKeyword": null
}

Rules:
- targetSections: which receipt sections contain item lines — typically ["body"], but ["header"] for shops like Lidl where items appear above the body separator
- totalTargetSections: leave [] to search the total in targetSections; set to e.g. ["body"] when items are in "header" but the total keyword appears in "body"
- nameXMax: x threshold below which tokens form the item name (left-aligned column)
- priceXMin/Max: x range where the final price appears (right-aligned column)
- excludeKeywords: lowercase German words for non-item lines (quantity sub-lines like "stk", deposits, coupons, subtotals)
- totalKeywords: German phrases that mark the grand total line (e.g. "gesamt", "zu zahlen")
- tableHeaderKeywords: if the receipt has a table with a header row, list the keywords that identify it (e.g. ["Artikel", "Preis"]); leave [] for column-based mode
- nameColumnKeyword / priceColumnKeyword: which header token marks each column; null in column-based mode
- Prices use comma as decimal separator (e.g. "1,99") and may have a trailing VAT class letter (e.g. "1,99 B") — handled automatically
- Output ONLY the JSON object, nothing else.
""".trimIndent()
    }
}
