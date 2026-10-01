package com.example.budgettracker.receipt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * Asserts that [result] matches an expected JSON like
 * `{ "processorId": "lidl", "items": [ { "name", "price", "quantity"?, "discount"? } ], "total": 12.34 }`.
 *
 * Items are checked by index; prices within ±0.005. A missing "quantity" means 1; a missing
 * "discount" means the parsed item must have none.
 */
fun assertReceipt(label: String, result: ParsedReceipt, expected: JSONObject) {
    if (expected.has("total") && !expected.isNull("total")) {
        assertNotNull("[$label] expected a total but got null", result.detectedTotal)
        assertEquals("[$label] total", expected.getDouble("total"), result.detectedTotal!!, 0.005)
    } else {
        assertNull("[$label] expected no total but got ${result.detectedTotal}", result.detectedTotal)
    }

    val items = expected.getJSONArray("items")
    val actual = result.items.joinToString("\n") { "  ${it.name} → ${it.price} qty=${it.quantity} discount=${it.discount}" }
    assertEquals("[$label] item count; parsed:\n$actual\n", items.length(), result.items.size)
    for (i in 0 until items.length()) {
        val exp = items.getJSONObject(i)
        val act = result.items[i]
        assertEquals("[$label] items[$i].name", exp.getString("name"), act.name)
        assertEquals("[$label] items[$i].price", exp.getDouble("price"), act.price, 0.005)
        assertEquals("[$label] items[$i].quantity", exp.optDouble("quantity", 1.0), act.quantity, 0.0005)
        if (exp.has("discount")) {
            assertNotNull("[$label] items[$i].discount missing", act.discount)
            assertEquals("[$label] items[$i].discount", exp.getDouble("discount"), act.discount!!, 0.005)
        } else {
            assertNull("[$label] items[$i] has unexpected discount", act.discount)
        }
    }
}
