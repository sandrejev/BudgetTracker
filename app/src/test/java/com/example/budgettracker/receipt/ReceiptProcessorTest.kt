package com.example.budgettracker.receipt

import com.example.budgettracker.data.ShopNameMatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ReceiptProcessor].
 *
 * Each test loads a fixture pair from src/test/resources/fixtures/:
 *   <name>.l0.json      — Level 0 JSON as it comes out of the image/PDF parser
 *   <name>.expected.json — expected parser output (processorId, items, total)
 *
 * Run with:  ./gradlew test
 */
class ReceiptProcessorTest {

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private fun resourceText(path: String): String =
        javaClass.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: error("Test resource not found: $path")

    /**
     * Load a fixture pair, run the named built-in processor, and return
     * (result, expectedJson) for further assertions.
     */
    private fun loadAndProcess(fixtureName: String): Pair<ParsedReceipt, JSONObject> {
        val l0Json = resourceText("/fixtures/$fixtureName.l0.json")
        val expectedJson = JSONObject(resourceText("/fixtures/$fixtureName.expected.json"))

        val doc = Level0Doc.fromJsonString(l0Json)
        val processorId = expectedJson.getString("processorId")
        val config = ProcessorConfig.ALL_BUILTIN.firstOrNull { it.id == processorId }
            ?: error("Unknown built-in processorId '$processorId' in fixture $fixtureName")

        return ReceiptProcessor.process(doc, config) to expectedJson
    }

    private fun assertResult(fixtureName: String, result: ParsedReceipt, expectedJson: JSONObject) =
        assertReceipt(fixtureName, result, expectedJson)

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * REWE single item with a trailing VAT-class letter on the price token
     * ("3,98 B" → 3.98). Verifies [parsePrice] strips the suffix correctly.
     */
    @Test
    fun reweSingleItemWithVatSuffix() {
        val (result, expected) = loadAndProcess("rewe_single_item")
        assertResult("rewe_single_item", result, expected)
    }

    /**
     * REWE receipt with:
     * - quantity sub-line ("2 Stk x 0,99")  → quantity 2 of the item above
     * - EUR/Pfand deposit line              → an item (deposits are part of the total)
     * - PAYBACK loyalty-points line         → excluded by "payback" keyword
     */
    @Test
    fun reweQuantityLinePfandAndPaybackExclusion() {
        val (result, expected) = loadAndProcess("rewe_with_stk")
        assertResult("rewe_with_stk", result, expected)
    }

    /**
     * Lidl receipt where items live in the "header" section and the total
     * ("Zu zahlen") is in the "body" section.
     * The "RABATT AKTION -0,30" line reduces the item above it.
     */
    @Test
    fun lidlItemsInHeaderSectionTotalInBody() {
        val (result, expected) = loadAndProcess("lidl_items")
        assertResult("lidl_items", result, expected)
    }

    /**
     * Müller receipt where:
     * - Prices use no-space VAT suffix: "2,79a" → 2.79.
     *   Verifies [parsePrice] strips the suffix even without preceding whitespace.
     * - "Zwischensumme" subtotal line excluded by keyword.
     * - "Müller Blüten" loyalty discount on the whole receipt → line with a negative price.
     * - "Kartenzahlung" payment-confirmation line excluded by "kartenzahlung" keyword.
     * - Total "ZU BEZAHLEN" detected correctly (1,98 after 2,00 discount).
     */
    @Test
    fun mullerNoSpaceVatSuffixAndDiscountExclusions() {
        val (result, expected) = loadAndProcess("muller_receipt")
        assertResult("muller_receipt", result, expected)
    }

    /**
     * Lidl eBon from a PDF with combined price+VAT tokens ("0,99 A") produced
     * by [Level0Builder.fromPdfLines] (2-space column splitting).
     * All items, including two Pfand deposit lines (included per Lidl config),
     * are in the "header" section; total "zu zahlen" is in the "body" section.
     * Verifies 6 items and grand total of 8,83.
     */
    @Test
    fun lidlEbonPdfFormatAllItemsIncludingPfand() {
        val (result, expected) = loadAndProcess("lidl_ebon")
        assertResult("lidl_ebon", result, expected)
    }

    /**
     * Lidl receipt (lidl_3.png) with discounts below items ("Lidl Plus Rabatt",
     * "Rabatt Getränke"), quantities in the item line ("0,65 x 2") and items on both sides
     * of the header/body boundary (Level0Builder starts the body at the first discount line).
     * The fixture is hand-made from the image, not real ML Kit output.
     */
    @Test
    fun lidlDiscountsQuantitiesAndItemsAcrossSections() {
        val (result, expected) = loadAndProcess("lidl_discounts")
        assertResult("lidl_discounts", result, expected)
    }

    @Test
    fun identicalItemsAreGroupedButNotWithADifferentDiscount() {
        val items = listOf(
            ParsedItem("Joghurt", 0.49),
            ParsedItem("Pfand 0,25 EM", 0.25),
            ParsedItem("Joghurt", 0.49),
            ParsedItem("Joghurt", 0.29, discount = 0.20),   // discount only on the third one
            ParsedItem("Pfand 0,25 EM", 0.50, quantity = 2.0),
            ParsedItem("Joghurt", 0.59)                     // different price
        )
        val grouped = groupItems(items)
        assertEquals(4, grouped.size)
        assertEquals(ParsedItem("Joghurt", 0.98, "\n", quantity = 2.0), grouped[0])
        assertEquals(3.0, grouped[1].quantity, 0.0)
        assertEquals(0.75, grouped[1].price, 0.0)
        assertEquals(0.25, grouped[1].unitPrice, 0.0)
        assertEquals(ParsedItem("Joghurt", 0.29, discount = 0.20), grouped[2])
        assertEquals(0.49, grouped[2].unitPrice, 0.0)
        assertEquals(0.59, grouped[3].price, 0.0)
    }

    /** Like lidl_discounts, but as the phone's OCR read it: the "2" of "0,65 x 2" is missing. */
    @Test
    fun lidlQuantityWithMissingCountIsDerivedFromPrice() {
        val l0 = resourceText("/fixtures/lidl_discounts.l0.json")
            .replace("""{"t": "x", "x": 0.648}, {"t": "2", "x": 0.735}, {"t": "1,30"""", """{"t": "x", "x": 0.648}, {"t": "1,30"""")
        require("{\"t\": \"1,30\"" in l0 && "{\"t\": \"2\", \"x\": 0.735}, {\"t\": \"1,30\"" !in l0) { "fixture changed" }
        val result = ReceiptProcessor.process(Level0Doc.fromJsonString(l0), ProcessorConfig.LIDL)
        val cola = result.items.single { it.name == "Cola 0% Zucker" }
        assertEquals(2.0, cola.quantity, 0.0)
        assertEquals(0.98, cola.price, 0.005)
        assertEquals(0.65, cola.unitPrice, 0.0)
    }

    /** The first discount line of lidl_3 as OCR may read it; Kohlrabi must always end up at 0,00. */
    @Test
    fun lidlKohlrabiDiscountSurvivesOcrVariants() {
        val original = """[{"t": "Lidl", "x": 0.2}, {"t": "Plus", "x": 0.309}, {"t": "Rabatt", "x": 0.44}, {"t": "-0,59", "x": 0.844}],"""
        val variants = mapOf(
            "misread keyword" to """[{"t": "Lidl", "x": 0.2}, {"t": "Plus", "x": 0.309}, {"t": "Rabalt", "x": 0.44}, {"t": "-0,59", "x": 0.844}],""",
            "only Lidl Plus" to """[{"t": "Lidl", "x": 0.2}, {"t": "Plus", "x": 0.309}, {"t": "Rbt", "x": 0.44}, {"t": "-0,59", "x": 0.844}],""",
            "en dash" to """[{"t": "Lidl", "x": 0.2}, {"t": "Plus", "x": 0.309}, {"t": "Rabatt", "x": 0.44}, {"t": "–0,59", "x": 0.844}],""",
            "minus apart" to """[{"t": "Lidl", "x": 0.2}, {"t": "Plus", "x": 0.309}, {"t": "Rabatt", "x": 0.44}, {"t": "-", "x": 0.79}, {"t": "0,59", "x": 0.86}],""",
            "outside column" to """[{"t": "Lidl", "x": 0.2}, {"t": "Plus", "x": 0.309}, {"t": "Rabatt", "x": 0.44}, {"t": "-0,59", "x": 0.78}],""",
            "amount on next line" to """[{"t": "Lidl", "x": 0.2}, {"t": "Plus", "x": 0.309}, {"t": "Rabatt", "x": 0.44}], [{"t": "-0,59", "x": 0.844}],""",
            "label unreadable" to """[{"t": "Ldl", "x": 0.2}, {"t": "Pus", "x": 0.309}], [{"t": "-0,59", "x": 0.844}],""",
        )
        val base = resourceText("/fixtures/lidl_discounts.l0.json")
        require(original in base) { "fixture changed" }
        val expected = JSONObject(resourceText("/fixtures/lidl_discounts.expected.json"))
        for ((label, line) in variants) {
            val doc = Level0Doc.fromJsonString(base.replaceFirst(original, line))
            assertReceipt("lidl_3 $label", ReceiptProcessor.process(doc, ProcessorConfig.LIDL), expected)
        }
    }

    /**
     * lidl_3 as the phone's OCR really read it: the Kohlrabi discount amount and Biokompost's
     * price are missing (recovered from the VAT table and the total), the "2" counts are
     * missing (derived from the unit price) and Brot's price reads "O,79".
     */
    @Test
    fun lidl3AsReadOnThePhone() {
        val doc = Level0Doc.fromJsonString(resourceText("/fixtures/lidl_3_device.l0.json"))
        val expected = JSONObject(resourceText("/fixtures/lidl_discounts.expected.json"))
        assertReceipt("lidl_3 device", ReceiptProcessor.process(doc, ProcessorConfig.LIDL), expected)
    }

    @Test
    fun discountKeywordsTolerateOneOcrError() {
        assertTrue(containsKeyword("Lidl Plus Rabalt", listOf("rabatt")))
        assertTrue(containsKeyword("Rabat Getränke", listOf("rabatt")))
        assertTrue(containsKeyword("Müller Bluten", listOf("blüten")))
        assertFalse(containsKeyword("Brot Bauernbag", listOf("rabatt", "preisvorteil")))
        assertFalse(containsKeyword("Kiwi Gold", listOf("rabatt")))
        assertEquals(-0.59, parsePrice("– 0,59")!!, 0.0)
        assertEquals(-0.59, parsePrice("−0,59")!!, 0.0)
        assertEquals(0.79, parsePrice("O,79")!!, 0.0)
    }

    @Test
    fun quantityPatterns() {
        assertEquals(2.0, findQuantity("2 Stk x 1,99")!!.amount, 0.0)
        assertEquals(1.99, findQuantity("2 Stk x 1,99")!!.unitPrice, 0.0)
        assertEquals(2.0, findQuantity("Cola 0% Zucker 0,65 x 2 1,30 B")!!.amount, 0.0)
        assertEquals(0.16, ParsedItem("Cola 0% Zucker", 0.98, quantity = 2.0, discount = 0.32).unitDiscount, 0.0)
        assertEquals(0.65, findQuantity("Cola 0% Zucker 0,65 x 2 1,30 B")!!.unitPrice, 0.0)
        assertEquals(0.436, findQuantity("0,436 kg x 2,99 EUR/kg")!!.amount, 0.0)
        assertNull(findQuantity("PFAND 0,25 EURO 0,25 A *"))
        // Quantities are checked against the line price
        assertEquals(2.0, resolveQuantity("Cola 0% Zucker 0,65 x 2 1,30 B", 1.30)!!, 0.0)
        assertEquals(2.0, resolveQuantity("2 Stk x 1,99", 3.98)!!, 0.0)
        assertEquals(0.436, resolveQuantity("0,436 kg x 2,99 EUR/kg", 1.30)!!, 0.0)
        // OCR missed or misread the count: "0,65 x 1,30" is not 0.65 units, it's 1,30 / 0,65 = 2
        assertEquals(2.0, resolveQuantity("Cola 0% Zucker 0,65 x 1,30 B", 1.30)!!, 0.0)
        assertEquals(2.0, resolveQuantity("Cola 0% Zucker 0,65 x Z 1,30 B", 1.30)!!, 0.0)
        // the count read out of order, after the line price and tax letter
        assertEquals(2.0, resolveQuantity("Cola 0% Zucker 0,65 x 1,30 2 B", 1.30)!!, 0.0)
        assertEquals(2.0, resolveQuantity("Cola 0% Zucker 0,65 x 1,30 B 2", 1.30)!!, 0.0)
        assertNull(resolveQuantity("Cola 0% Zucker 1,30 B", 1.30))
        assertNull(resolveQuantity("Rucola 125g 0,71 A", 0.71))
        assertNull(findQuantity("Rucola 125g 0,71 A"))
        assertEquals(0.25, parsePrice("0,25 A *")!!, 0.0)
    }

    /**
     * The shop name is fuzzy-matched against saved shops using the first header
     * lines: OCR noise ("LGDL"), extra words ("MH Müller Handels GmbH") and a
     * shop name that isn't on the first line (REWE under "Klingenberg oHG").
     */
    @Test
    fun shopNameIsMatchedFromHeaderLines() {
        val shops = listOf("LIDL", "Müller", "REWE", "Penny")
        fun shopFor(fixture: String): String? {
            val doc = Level0Doc.fromJsonString(resourceText("/fixtures/$fixture.l0.json"))
            return ShopNameMatcher.bestMatch(ReceiptProcessor.headerLines(doc), shops)
        }
        assertEquals("LIDL", shopFor("lidl_items"))
        assertEquals("LIDL", shopFor("lidl_ebon"))
        assertEquals("Müller", shopFor("muller_receipt"))
        assertEquals("REWE", shopFor("rewe_single_item"))
        assertEquals("REWE", shopFor("rewe_with_stk"))
    }
}
