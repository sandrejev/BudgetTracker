package com.example.budgettracker.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Noise patterns to drop
private val NOISE_PATTERNS = listOf(
    Regex("""^[A-Z0-9]{20,}$"""),                         // TSE / barcode long hex strings
    Regex("""^\d{8,}$"""),                                  // Pure long digit strings (barcodes)
    Regex("""^[=\-_]{4,}$"""),                              // Separator-only lines
    Regex("""(?i)^(tse|kundennummer|kundenkarte|mwst.?nr|ust.?id|tel|fax|www\.|http)"""),
)
// Keywords that signal start of VAT section
private val VAT_SECTION_KEYWORDS = listOf("mwst", "steuer", "netto", "brutto", "ust")
// Keywords for body-section start
private val PRICE_PATTERN = Regex("""\d[,.]?\d{2}$""")

/**
 * Converts a PNG/JPEG image URI into a Level 0 document using on-device MLKit OCR.
 */
object Level0Builder {

    /** Build a Level 0 doc from a file-backed URI (the normal app path). */
    suspend fun fromImageUri(context: Context, uri: Uri): Level0Doc {
        val image = InputImage.fromFilePath(context, uri)
        val pageWidth = image.width.takeIf { it > 0 } ?: 1080
        return fromInputImage(image, pageWidth)
    }

    /**
     * Build a Level 0 doc directly from a [Bitmap].
     * Used by instrumented tests so they can load images from assets without
     * needing a content:// URI.
     */
    suspend fun fromBitmap(bitmap: Bitmap): Level0Doc {
        val image = InputImage.fromBitmap(bitmap, 0)
        val pageWidth = bitmap.width.takeIf { it > 0 } ?: 1080
        return fromInputImage(image, pageWidth)
    }

    /** Common MLKit OCR path shared by [fromImageUri] and [fromBitmap]. */
    private suspend fun fromInputImage(image: InputImage, pageWidth: Int): Level0Doc {
        val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val visionText = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        // ── Collect all "elements" (words) with bounding boxes ────────────────
        data class WordBox(val text: String, val rect: Rect)

        val words = mutableListOf<WordBox>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val bbox = el.boundingBox ?: continue
                    val txt = el.text.trim()
                    if (txt.isBlank()) continue
                    words += WordBox(txt, bbox)
                }
            }
        }
        if (words.isEmpty()) return Level0Doc("png", pageWidth, emptyList())

        // ── Cluster words into visual lines by Y centre ───────────────────────
        val lineHeight = words.map { it.rect.height() }.average().toFloat().coerceAtLeast(10f)
        val tolerance = lineHeight * 0.6f

        data class VisLine(val yCentre: Float, val wordList: MutableList<WordBox>)

        val visLines = mutableListOf<VisLine>()
        for (w in words.sortedBy { it.rect.centerY() }) {
            val yc = w.rect.centerY().toFloat()
            val existing = visLines.firstOrNull { Math.abs(it.yCentre - yc) <= tolerance }
            if (existing != null) {
                existing.wordList += w
            } else {
                visLines += VisLine(yc, mutableListOf(w))
            }
        }
        visLines.sortBy { it.yCentre }

        // ── Build tokens for each line ────────────────────────────────────────
        data class RawLine(val tokens: List<L0Token>)

        val rawLines = visLines.map { vl ->
            val sorted = vl.wordList.sortedBy { it.rect.centerX() }
            val tokens = sorted.map { w ->
                L0Token(w.text, w.rect.centerX().toFloat() / pageWidth)
            }
            RawLine(tokens)
        }

        // ── Filter noise ──────────────────────────────────────────────────────
        val filtered = rawLines.filter { raw ->
            val joined = raw.tokens.joinToString(" ") { it.text }
            NOISE_PATTERNS.none { it.containsMatchIn(joined) }
        }

        // ── Detect sections ───────────────────────────────────────────────────
        return buildSections("png", pageWidth, filtered.map { it.tokens })
    }

    /**
     * Converts raw text from a PDF (extracted line by line) into a Level 0 document.
     * Each element in [lines] is one text line from the PDF.
     */
    fun fromPdfLines(lines: List<String>, pageWidth: Int = 500): Level0Doc {
        // For PDF text, we don't have X positions from a fixed page.
        // We approximate X by character position relative to line width.
        val tokenLines: List<List<L0Token>> = lines.map { line ->
            if (line.isBlank()) return@map emptyList()
            // Split on 2+ spaces to preserve column structure
            val parts = line.split(Regex("  +")).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return@map emptyList()
            val totalLen = line.length.toFloat().coerceAtLeast(1f)
            parts.map { part ->
                val pos = line.indexOf(part).toFloat() / totalLen
                L0Token(part, pos.coerceIn(0f, 1f))
            }
        }.filter { it.isNotEmpty() }

        // Filter noise
        val filtered = tokenLines.filter { tokens ->
            val joined = tokens.joinToString(" ") { it.text }
            NOISE_PATTERNS.none { it.containsMatchIn(joined) }
        }

        return buildSections("pdf", pageWidth, filtered)
    }

    private fun buildSections(source: String, pageWidth: Int, tokenLines: List<List<L0Token>>): Level0Doc {
        if (tokenLines.isEmpty()) return Level0Doc(source, pageWidth, emptyList())

        // Decide where header ends and body starts:
        // Body starts at first line that contains a price-looking token
        var bodyStart = 0
        for (i in tokenLines.indices) {
            val joined = tokenLines[i].joinToString(" ") { it.text }
            if (PRICE_PATTERN.containsMatchIn(joined)) { bodyStart = i; break }
            if (i == tokenLines.lastIndex) bodyStart = 0
        }

        // VAT section starts when we see VAT-section keywords
        var vatStart = tokenLines.size
        for (i in bodyStart until tokenLines.size) {
            val joined = tokenLines[i].joinToString(" ") { it.text }.lowercase()
            if (VAT_SECTION_KEYWORDS.any { joined.contains(it) } &&
                i > bodyStart + 2 // avoid false positives early on
            ) {
                vatStart = i; break
            }
        }

        val sections = mutableListOf<L0Section>()

        // Header
        if (bodyStart > 0) {
            val headerLines = tokenLines.subList(0, bodyStart).toL0Lines()
            if (headerLines.isNotEmpty()) sections += L0Section("header", headerLines)
        }

        // Body
        run {
            val bodyTokenLines = tokenLines.subList(bodyStart, vatStart)
            val bodyLines = bodyTokenLines.toL0LinesWithSeps()
            if (bodyLines.isNotEmpty()) sections += L0Section("body", bodyLines)
        }

        // VAT
        if (vatStart < tokenLines.size) {
            val vatLines = tokenLines.subList(vatStart, tokenLines.size).toL0Lines()
            if (vatLines.isNotEmpty()) sections += L0Section("vat", vatLines)
        }

        return Level0Doc(source, pageWidth, sections)
    }

    /** Convert token-lines to L0Lines, optionally detecting separator lines. */
    private fun List<List<L0Token>>.toL0LinesWithSeps(): List<L0Line> = flatMap { tokens ->
        val joined = tokens.joinToString("") { it.text }
        if (joined.matches(Regex("""[-=_*]{3,}"""))) listOf(L0Line.Separator)
        else listOf(L0Line.Tokens(tokens))
    }

    private fun List<List<L0Token>>.toL0Lines(): List<L0Line> =
        map { L0Line.Tokens(it) }
}
