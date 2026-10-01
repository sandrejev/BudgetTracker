package com.example.budgettracker.receipt

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-based configuration for parsing a Level 0 receipt into items and total.
 *
 * Two item-extraction strategies are supported and selected automatically:
 *
 * ── Column-based (default) ────────────────────────────────────────────────────
 * Token x-coordinates are compared against [nameXMax] and [priceXMin]/[priceXMax].
 * Simple and fast; works well when the receipt has a fixed two-column layout.
 *
 * ── Table-based (when [tableHeaderKeywords] is non-empty) ────────────────────
 * The processor scans for a header row that contains all of [tableHeaderKeywords]
 * (e.g. "Artikel" and "Preis" on the same line).  When found, it records the
 * x-positions of [nameColumnKeyword] and [priceColumnKeyword] tokens and uses
 * those positions to extract name and price from each subsequent item row.
 * This is self-calibrating: it does not depend on fixed x thresholds, so it
 * works regardless of image crop or phone resolution.
 * A [L0Line.Separator] resets table mode so only the relevant table is parsed.
 */
data class ProcessorConfig(
    val id: String,
    val name: String,
    /** Regex patterns matched against shop name to auto-select this processor. */
    val shopNamePatterns: List<String> = emptyList(),
    /** Which L0 section types to scan for item rows (default: body only). */
    val targetSections: List<String> = listOf("body"),
    /**
     * Which L0 section types to scan for the total line.
     * Empty = same as [targetSections] (the common case).
     * Set to a different list when items and total live in separate sections
     * (e.g. Lidl: items in "header", total keyword in "body").
     */
    val totalTargetSections: List<String> = emptyList(),
    /** Tokens with x ≤ nameXMax are concatenated as the item name (column-based mode). */
    val nameXMax: Float = 0.65f,
    /** Tokens with x ≥ priceXMin are candidate price tokens (column-based mode). */
    val priceXMin: Float = 0.65f,
    /** Tokens with x ≤ priceXMax are candidate price tokens (column-based mode). */
    val priceXMax: Float = 0.96f,
    /** Item lines containing these strings (case-insensitive) are skipped. */
    val excludeKeywords: List<String> = emptyList(),
    /**
     * A line with a price containing one of these (case-insensitive) is a discount on the
     * item above it, e.g. Lidl's "Lidl Plus Rabatt -0,59": the item's price is reduced.
     */
    val discountKeywords: List<String> = DEFAULT_DISCOUNT_KEYWORDS,
    /**
     * A line with a price containing one of these is a discount on the whole receipt, e.g.
     * Müller's "Müller Blüten 2,00" after the subtotal; it becomes a line with a negative price.
     */
    val receiptDiscountKeywords: List<String> = emptyList(),
    /** Line containing any of these keywords is the total line. */
    val totalKeywords: List<String> = listOf("gesamt", "total", "summe", "zu zahlen", "zu bezahlen"),
    /** Total price token must have x ≥ this value. */
    val totalPriceXMin: Float = 0.55f,

    // ── Table-based strategy ──────────────────────────────────────────────────
    /**
     * If non-empty, enables table mode.  When a line contains ALL of these
     * keywords (case-insensitive), that line is treated as the table header and
     * its token x-positions are recorded for column-aligned extraction.
     * Example: listOf("Artikel", "Preis") for Müller Bonnachdruck receipts.
     */
    val tableHeaderKeywords: List<String> = emptyList(),
    /**
     * Which token in the header row marks the item-name column.
     * Tokens from this column's x position (± 5%) up to (but not including)
     * the price column are concatenated as the item name.
     */
    val nameColumnKeyword: String? = null,
    /**
     * Which token in the header row marks the price column.
     * The item price is the parseable price token closest (by x) to this position.
     */
    val priceColumnKeyword: String? = null,

    val isBuiltIn: Boolean = false
) {
    fun matchesShopName(shopName: String): Boolean =
        shopNamePatterns.any { pattern ->
            shopName.contains(Regex(pattern, RegexOption.IGNORE_CASE))
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        if (shopNamePatterns.isNotEmpty()) put("shopNamePatterns", JSONArray(shopNamePatterns))
        if (targetSections != listOf("body")) put("targetSections", JSONArray(targetSections))
        if (totalTargetSections.isNotEmpty()) put("totalTargetSections", JSONArray(totalTargetSections))
        put("nameXMax", nameXMax.toDouble())
        put("priceXMin", priceXMin.toDouble())
        put("priceXMax", priceXMax.toDouble())
        if (excludeKeywords.isNotEmpty()) put("excludeKeywords", JSONArray(excludeKeywords))
        put("discountKeywords", JSONArray(discountKeywords))
        if (receiptDiscountKeywords.isNotEmpty()) put("receiptDiscountKeywords", JSONArray(receiptDiscountKeywords))
        put("totalKeywords", JSONArray(totalKeywords))
        put("totalPriceXMin", totalPriceXMin.toDouble())
        if (tableHeaderKeywords.isNotEmpty()) put("tableHeaderKeywords", JSONArray(tableHeaderKeywords))
        if (nameColumnKeyword != null) put("nameColumnKeyword", nameColumnKeyword)
        if (priceColumnKeyword != null) put("priceColumnKeyword", priceColumnKeyword)
    }

    fun toJsonString(): String = toJson().toString(2)

    companion object {
        val DEFAULT_DISCOUNT_KEYWORDS = listOf("rabatt", "preisvorteil")

        fun fromJson(json: JSONObject, builtIn: Boolean = false): ProcessorConfig {
            fun strList(key: String): List<String> {
                val a = json.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }
            return ProcessorConfig(
                id = json.getString("id"),
                name = json.getString("name"),
                shopNamePatterns = strList("shopNamePatterns"),
                targetSections = strList("targetSections").ifEmpty { listOf("body") },
                totalTargetSections = strList("totalTargetSections"),
                nameXMax = json.optDouble("nameXMax", 0.65).toFloat(),
                priceXMin = json.optDouble("priceXMin", 0.65).toFloat(),
                priceXMax = json.optDouble("priceXMax", 0.96).toFloat(),
                excludeKeywords = strList("excludeKeywords"),
                // Configs saved before discounts existed get the defaults
                discountKeywords = if (json.has("discountKeywords")) strList("discountKeywords")
                    else DEFAULT_DISCOUNT_KEYWORDS,
                receiptDiscountKeywords = strList("receiptDiscountKeywords"),
                totalKeywords = strList("totalKeywords").ifEmpty {
                    listOf("gesamt", "total", "summe", "zu zahlen", "zu bezahlen")
                },
                totalPriceXMin = json.optDouble("totalPriceXMin", 0.55).toFloat(),
                tableHeaderKeywords = strList("tableHeaderKeywords"),
                nameColumnKeyword = json.optString("nameColumnKeyword").ifEmpty { null },
                priceColumnKeyword = json.optString("priceColumnKeyword").ifEmpty { null },
                isBuiltIn = builtIn
            )
        }

        fun fromJsonString(json: String, builtIn: Boolean = false): ProcessorConfig =
            fromJson(JSONObject(json), builtIn)

        // ── Built-in configs ──────────────────────────────────────────────────

        val LIDL = ProcessorConfig(
            id = "lidl",
            name = "Lidl",
            // OCR often reads the stylised Lidl logo as LGDL or similar
            shopNamePatterns = listOf("(?i)l[gi]dl", "(?i)lidl"),
            // Items and the total are read from header and body: where Level0Builder puts
            // the header/body boundary depends on the receipt (e.g. the first discount line)
            targetSections = listOf("header", "body"),
            // Item names end around x≈0.37; 0.50 gives a safe margin
            nameXMax = 0.50f,
            // Prices right-aligned at x≈0.855; VAT letter (A/B) at x≈0.931 excluded by priceXMax
            priceXMin = 0.82f,
            priceXMax = 0.92f,
            // Pfand (deposit) IS included in the receipt total — do not exclude it
            excludeKeywords = listOf("gutschein", "coupon", "rabatt", "preisvorteil", "bonus"),
            // "Lidl Plus Rabatt -0,59", "Rabatt Getränke -0,32" below an item
            discountKeywords = listOf("rabatt", "preisvorteil", "lidl plus"),
            totalKeywords = listOf("zu zahlen", "betrag", "gesamt", "total"),
            totalPriceXMin = 0.75f,
            isBuiltIn = true
        )

        val MULLER = ProcessorConfig(
            id = "muller",
            name = "Müller",
            shopNamePatterns = listOf("(?i)m.ller", "(?i)mueller"),
            // Items live in header AND body (Bonnachdruck table spans both sections);
            // total keyword is in the body.
            targetSections = listOf("header", "body"),
            totalTargetSections = listOf("body"),
            // Fallback column thresholds (used only if table header is not found)
            nameXMax = 0.55f,
            priceXMin = 0.75f,
            priceXMax = 0.97f,
            excludeKeywords = listOf(
                "zwischensumme",
                // OCR sometimes splits "Zwischensumme" into "Zwis" + "chensumme" —
                // add the suffix so the subtotal line is excluded either way.
                "chensumme",
                "pfand", "gutschein", "rabatt",
                "kartenzahlung", "nachlass"
            ),
            // "Müller Blüten 2,00" after the subtotal: loyalty discount on the whole receipt
            receiptDiscountKeywords = listOf("blüten"),
            // "summe" removed: it substring-matches "ZWISCHENSUMME" and picks up the subtotal
            // instead of the final "ZU BEZAHLEN" amount.
            totalKeywords = listOf("zu bezahlen", "gesamt", "total"),
            // Accept the total price at any x position — the ZU BEZAHLEN amount may land
            // anywhere depending on image crop.
            totalPriceXMin = 0.0f,
            // Table-based strategy: find the Bonnachdruck column headers, then use
            // their x-positions to extract Artikel (name) and Preis (price) per row.
            tableHeaderKeywords = listOf("Artikel", "Preis"),
            nameColumnKeyword = "Artikel",
            priceColumnKeyword = "Preis",
            isBuiltIn = true
        )

        val REWE = ProcessorConfig(
            id = "rewe",
            name = "Rewe",
            shopNamePatterns = listOf("(?i)rewe"),
            // Item lines end with a VAT letter ("3,98 B"), so Level0Builder may put them in
            // the header; read items and total from both sections
            targetSections = listOf("header", "body"),
            nameXMax = 0.70f,
            priceXMin = 0.68f,
            priceXMax = 0.97f,
            // Pfand (deposit) is part of the total, so it's an item.
            // "2 Stk x 1,99" lines below an item become its quantity.
            excludeKeywords = listOf("coupon", "treuepunkte", "payback"),
            totalKeywords = listOf("summe", "gesamt", "zu zahlen", "total"),
            totalPriceXMin = 0.55f,
            isBuiltIn = true
        )

        val PENNY = ProcessorConfig(
            id = "penny",
            name = "Penny",
            shopNamePatterns = listOf("(?i)penny"),
            // Same receipt layout as Rewe: read items and total from header and body
            targetSections = listOf("header", "body"),
            nameXMax = 0.72f,
            priceXMin = 0.70f,
            priceXMax = 0.97f,
            // Pfand (deposit) is part of the total, so it's an item
            excludeKeywords = listOf("coupon", "punkte"),
            totalKeywords = listOf("summe", "gesamt", "zu zahlen", "total"),
            totalPriceXMin = 0.55f,
            isBuiltIn = true
        )

        val ALL_BUILTIN: List<ProcessorConfig> = listOf(LIDL, MULLER, REWE, PENNY)
    }
}
