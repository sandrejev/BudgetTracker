package com.example.budgettracker.receipt

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * Extracts plain text lines from a PDF that contains real embedded text (not scanned).
 * Requires the pdfbox-android dependency.
 */
object PdfTextExtractor {

    private var initialized = false

    fun init(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context)
            initialized = true
        }
    }

    /**
     * Returns the text of all pages, split into individual lines.
     * Lines are trimmed; blank lines are removed.
     */
    fun extractLines(context: Context, uri: Uri): List<String> {
        init(context)
        val stream: InputStream = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open PDF: $uri")
        return stream.use { input ->
            val doc: PDDocument = PDDocument.load(input)
            doc.use {
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                textToLines(stripper.getText(doc))
            }
        }
    }

    /**
     * Splits PDFTextStripper output into lines (trailing spaces trimmed, blank lines removed).
     * Shared with the JVM tests, which extract the text with desktop PDFBox.
     */
    fun textToLines(text: String): List<String> =
        text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }

    /**
     * Full pipeline: PDF URI → Level 0 document.
     */
    fun toLevel0(context: Context, uri: Uri): Level0Doc {
        val lines = extractLines(context, uri)
        return Level0Builder.fromPdfLines(lines)
    }
}
