package com.example.budgettracker.receipt

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Runs every example PDF receipt in app/src/androidTest/assets/receipts/ through the
 * same steps as the app (text extraction → [Level0Builder.fromPdfLines] →
 * [ReceiptProcessor]) and compares with its <name>.expected.json.
 *
 * Text is extracted with desktop PDFBox, which matches pdfbox-android (same version).
 * Run with:  ./gradlew test
 */
class PdfReceiptTest {

    // Gradle and Android Studio run unit tests with the module (app/) as working directory
    private val receiptsDir = File("src/androidTest/assets/receipts")

    private fun pdfLines(file: File): List<String> = PDDocument.load(file).use { doc ->
        val stripper = PDFTextStripper()
        stripper.sortByPosition = true
        PdfTextExtractor.textToLines(stripper.getText(doc))
    }

    @Test
    fun allPdfReceipts() {
        val pdfs = receiptsDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("No PDF receipts found in ${receiptsDir.absolutePath}", pdfs.isNotEmpty())

        val failures = mutableListOf<String>()
        for (pdf in pdfs) {
            val lines = pdfLines(pdf)
            val doc = Level0Builder.fromPdfLines(lines)
            val expectedFile = File(receiptsDir, pdf.nameWithoutExtension + ".expected.json")
            if (!expectedFile.exists()) {
                failures += "[${pdf.name}] No ${expectedFile.name}. Level 0 JSON:\n${doc.toJsonString()}"
                continue
            }
            val expected = JSONObject(expectedFile.readText())
            val processorId = expected.getString("processorId")
            val config = ProcessorConfig.ALL_BUILTIN.firstOrNull { it.id == processorId }
                ?: error("Unknown processorId '$processorId' in ${expectedFile.name}")
            try {
                assertReceipt(pdf.name, ReceiptProcessor.process(doc, config), expected)
            } catch (e: AssertionError) {
                failures += "${e.message}\nLevel 0 JSON:\n${doc.toJsonString()}"
            }
        }
        if (failures.isNotEmpty()) fail("\n\n" + failures.joinToString("\n\n---\n\n"))
    }
}
