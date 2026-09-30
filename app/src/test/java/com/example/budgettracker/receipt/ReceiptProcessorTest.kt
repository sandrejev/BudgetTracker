package com.example.budgettracker.receipt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * Assert that [result] matches [expectedJson].
     * Items are checked by index: name (exact) and price (within ±0.005).
     */
    private fun assertResult(fixtureName: String, result: ParsedReceipt, expectedJson: JSONObject) {
        // ── Total ─────────────────────────────────────────────────────────────
        if (expectedJson.has("total") && !expectedJson.isNull("total")) {
            val expectedTotal = expectedJson.getDouble("total")
            assertNotNull("[$fixtureName] expected a total but got null", result.detectedTotal)
            assertEquals(
                "[$fixtureName] total",
                expectedTotal,
                result.detectedTotal!!,
                0.005
            )
        } else {
            assertNull("[$fixtureName] expected no total but got ${result.detectedTotal}", result.detectedTotal)
        }

        // ── Items ─────────────────────────────────────────────────────────────
        val expectedItems = expectedJson.getJSONArray("items")
        assertEquals(
            "[$fixtureName] item count",
            expectedItems.length(),
            result.items.size
        )

        for (i in 0 until expectedItems.length()) {
            val exp = expectedItems.getJSONObject(i)
            val act = result.items[i]
            assertEquals("[$fixtureName] items[$i].name", exp.getString("name"), act.name)
            assertEquals("[$fixtureName] items[$i].price", exp.getDouble("price"), act.price, 0.005)
        }
    }

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
     * - quantity sub-lines ("2 Stk x 0,99") → excluded by "stk" keyword
     * - EUR/Pfand deposit line                → excluded by "eur" and "pfand" keywords
     * - PAYBACK loyalty-points line           → excluded by "payback" keyword
     * Verifies the correct 3 items are extracted and excluded lines are ignored.
     */
    @Test
    fun reweExcludesStk_Pfand_Payback() {
        val (result, expected) = loadAndProcess("rewe_with_stk")
        assertResult("rewe_with_stk", result, expected)
    }

    /**
     * Lidl receipt where items live in the "header" section and the total
     * ("Zu zahlen") is in the "body" section.
     * A rabatt (discount) line in the header is excluded by keyword.
     * Verifies [ProcessorConfig.LIDL] with cross-section scanning.
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
     * - "Müller Blüten" loyalty-discount line excluded by "blüten" keyword.
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
}
