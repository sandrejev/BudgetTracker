package com.example.budgettracker.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
        val bitmap = decodeUpright(context, uri)
            ?: return fromInputImage(InputImage.fromFilePath(context, uri))
        return fromBitmap(bitmap)
    }

    /**
     * Build a Level 0 doc directly from a [Bitmap] (also used by the instrumented tests).
     *
     * OCR runs twice: on a black-and-white version of the receipt (coloured print such as
     * Lidl's blue discount lines turned black, see [OcrImage.binarize]) and on the image as
     * it is. The black-and-white words are used, plus any word only the second pass found.
     */
    suspend fun fromBitmap(bitmap: Bitmap): Level0Doc {
        val pageWidth = bitmap.width.takeIf { it > 0 } ?: 1080
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val original = recognize(recognizer, InputImage.fromBitmap(bitmap, 0), "orig")
            val blackAndWhite = try {
                val scale = OcrImage.scaleFor(bitmap.width, bitmap.height)
                val bw = blackAndWhite(bitmap, scale)
                with(OcrImage) { recognize(recognizer, InputImage.fromBitmap(bw, 0), "bw").map { it.unscaled(scale) } }
            } catch (e: OutOfMemoryError) {
                emptyList()  // a huge photo: the original pass alone still works
            }
            return buildFromWords(OcrImage.mergeWords(blackAndWhite, original), pageWidth)
        } finally {
            recognizer.close()
        }
    }

    /** Fallback when the image can't be decoded as a Bitmap: one OCR pass, as ML Kit reads it. */
    private suspend fun fromInputImage(image: InputImage): Level0Doc {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return buildFromWords(recognize(recognizer, image, "orig"), image.width.takeIf { it > 0 } ?: 1080)
        } finally {
            recognizer.close()
        }
    }

    /** Scaled black-and-white copy of [bitmap] for OCR. */
    private fun blackAndWhite(bitmap: Bitmap, scale: Float): Bitmap {
        val (w, h) = OcrImage.scaledSize(bitmap.width, bitmap.height, scale)
        val scaled = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        val bw = OcrImage.binarize(pixels, w, h)
        return Bitmap.createBitmap(bw, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Decodes [uri] as a software Bitmap turned upright by its EXIF orientation (camera photos
     * are often stored sideways). Very large photos are subsampled. Null if it can't be read.
     */
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_DECODE_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        decoded?.let { rotate(it, orientation) }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private const val MAX_DECODE_PIXELS = 16_000_000L

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** All words ML Kit finds in [image], with their boxes and confidence. */
    private suspend fun recognize(recognizer: TextRecognizer, image: InputImage, pass: String): List<OcrImage.Word> {
        val visionText = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val words = mutableListOf<OcrImage.Word>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val box = el.boundingBox ?: continue
                    val txt = el.text.trim()
                    if (txt.isBlank()) continue
                    words += OcrImage.Word(
                        txt, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(),
                        el.confidence, pass
                    )
                }
            }
        }
        return words
    }

    /** Groups OCR words into lines and sections. */
    private fun buildFromWords(words: List<OcrImage.Word>, pageWidth: Int): Level0Doc {
        if (words.isEmpty()) return Level0Doc("png", pageWidth, emptyList())

        // ── Cluster words into visual lines by Y centre ───────────────────────
        val lineHeight = words.map { it.height }.average().toFloat().coerceAtLeast(10f)
        val tolerance = lineHeight * 0.6f

        data class VisLine(val yCentre: Float, val wordList: MutableList<OcrImage.Word>)

        val visLines = mutableListOf<VisLine>()
        for (w in words.sortedBy { it.centerY }) {
            val yc = w.centerY
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
            val sorted = vl.wordList.sortedBy { it.centerX }
            val tokens = sorted.map { w ->
                L0Token(w.text, w.centerX / pageWidth, w.confidence)
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
