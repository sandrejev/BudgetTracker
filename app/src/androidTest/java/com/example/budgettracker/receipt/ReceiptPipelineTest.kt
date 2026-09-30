package com.example.budgettracker.receipt

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented receipt-pipeline tests — run on a device or emulator.
 *
 * Place receipt images in:
 *   app/src/androidTest/assets/receipts/
 *   (jpg, jpeg, or png)
 *
 * For each image, create a matching expected file in the same folder:
 *   <name>.expected.json   →  { "processorId": "rewe", "items": [...], "total": 12.34 }
 *
 * If no expected file exists yet, the test fails and prints the Level 0 JSON
 * produced by OCR — paste that into the app, verify, then create the expected file.
 *
 * Run with:  ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ReceiptPipelineTest {

    private val assets
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    // ── Image discovery ───────────────────────────────────────────────────────

    private fun receiptImageNames(): List<String> =
        (assets.list("receipts") ?: emptyArray())
            .filter { name ->
                name.endsWith(".jpg", ignoreCase = true) ||
                name.endsWith(".jpeg", ignoreCase = true) ||
                name.endsWith(".png", ignoreCase = true)
            }
            .sorted()

    // ── Pipeline ──────────────────────────────────────────────────────────────

    /**
     * OCR → Level0Doc, then load expected JSON if it exists.
     * Returns (doc, result, expectedJson) — result/expectedJson are null when
     * no expected file was found.
     */
    private data class PipelineResult(
        val doc: Level0Doc,
        val result: ParsedReceipt?,
        val expectedJson: JSONObject?
    )

    private fun runPipeline(imageName: String): PipelineResult {
        val bitmap = assets.open("receipts/$imageName").use { stream ->
            BitmapFactory.decodeStream(stream)
                ?: error("Could not decode receipts/$imageName — is it a valid image?")
        }

        val doc = runBlocking { Level0Builder.fromBitmap(bitmap) }

        val expectedName = imageName.substringBeforeLast('.') + ".expected.json"
        val expectedText = try {
            assets.open("receipts/$expectedName").bufferedReader().readText()
        } catch (_: Exception) {
            return PipelineResult(doc, null, null)
        }

        val expectedJson = JSONObject(expectedText)
        val processorId = expectedJson.getString("processorId")
        val config = ProcessorConfig.ALL_BUILTIN.firstOrNull { it.id == processorId }
            ?: error("Unknown processorId '$processorId' in $expectedName — valid ids: " +
                     ProcessorConfig.ALL_BUILTIN.joinToString { it.id })

        return PipelineResult(doc, ReceiptProcessor.process(doc, config), expectedJson)
    }

    // ── Assertions ────────────────────────────────────────────────────────────

    private fun assertResult(imageName: String, result: ParsedReceipt, expectedJson: JSONObject) {
        // Total
        if (expectedJson.has("total") && !expectedJson.isNull("total")) {
            val expectedTotal = expectedJson.getDouble("total")
            assertNotNull("[$imageName] expected a total but got null", result.detectedTotal)
            assertEquals("[$imageName] total", expectedTotal, result.detectedTotal!!, 0.005)
        } else {
            assertNull(
                "[$imageName] expected no total but got ${result.detectedTotal}",
                result.detectedTotal
            )
        }

        // Items
        val expectedItems = expectedJson.getJSONArray("items")
        assertEquals("[$imageName] item count", expectedItems.length(), result.items.size)
        for (i in 0 until expectedItems.length()) {
            val exp = expectedItems.getJSONObject(i)
            val act = result.items[i]
            assertEquals("[$imageName] items[$i].name", exp.getString("name"), act.name)
            assertEquals("[$imageName] items[$i].price", exp.getDouble("price"), act.price, 0.005)
        }
    }

    // ── Entry-point test ──────────────────────────────────────────────────────

    /**
     * Runs every image found in assets/receipts/.
     * Images without an expected.json are reported as failures with the L0 JSON
     * printed so you can inspect it and create the expected file.
     */
    @Test
    fun allReceiptImages() {
        val images = receiptImageNames()
        if (images.isEmpty()) {
            println("ReceiptPipelineTest: no images in assets/receipts/ — nothing to test")
            return
        }

        val failures = mutableListOf<String>()

        for (imageName in images) {
            val pipelineResult = try {
                runPipeline(imageName)
            } catch (e: Exception) {
                failures += "[$imageName] Pipeline threw: ${e.message}"
                continue
            }

            if (pipelineResult.result == null) {
                // No expected.json — dump L0 JSON so user can create the expected file
                val l0Json = pipelineResult.doc.toJsonString(indent = 2)
                failures += buildString {
                    appendLine("[$imageName] No expected.json found.")
                    appendLine("Create: app/src/androidTest/assets/receipts/${imageName.substringBeforeLast('.')}.expected.json")
                    appendLine("with content: { \"processorId\": \"<rewe|lidl|muller|...>\", \"items\": [...], \"total\": ... }")
                    appendLine()
                    appendLine("Level 0 JSON from OCR:")
                    append(l0Json)
                }
                continue
            }

            try {
                assertResult(imageName, pipelineResult.result, pipelineResult.expectedJson!!)
            } catch (e: AssertionError) {
                failures += buildString {
                    appendLine(e.message)
                    appendLine("Actual items:")
                    pipelineResult.result.items.forEach { appendLine("  ${it.name} → ${it.price}") }
                    appendLine("Actual total: ${pipelineResult.result.detectedTotal}")
                    appendLine()
                    appendLine("Level 0 JSON from OCR:")
                    append(pipelineResult.doc.toJsonString(indent = 2))
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("\n\n" + failures.joinToString("\n\n---\n\n"))
        }
    }
}
