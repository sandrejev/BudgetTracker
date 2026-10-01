package com.example.budgettracker.receipt

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Image preparation for receipt OCR and merging of two OCR passes. Plain Kotlin (no Android
 * types) so it can be unit tested; Level0Builder does the Bitmap and ML Kit side.
 */
object OcrImage {

    /** Smallest width worth reading; narrower images are scaled up (small text reads badly). */
    const val MIN_WIDTH = 1600
    /** Largest image to binarize (pixels); larger photos are scaled down. */
    const val MAX_PIXELS = 5_000_000

    /** Scale factor that brings a [width] × [height] image into the size range above. */
    fun scaleFor(width: Int, height: Int): Float {
        val pixels = width.toLong() * height
        val down = if (pixels > MAX_PIXELS) sqrt(MAX_PIXELS.toDouble() / pixels).toFloat() else 1f
        val up = if (width < MIN_WIDTH) MIN_WIDTH.toFloat() / width else 1f
        // Never scale up past the pixel limit
        val upCapped = min(up, sqrt(MAX_PIXELS.toDouble() / pixels).toFloat().coerceAtLeast(1f))
        return if (down < 1f) down else upCapped
    }

    /**
     * Black-and-white version of an ARGB image for OCR.
     *
     * - "Ink" is the darkest colour channel of a pixel, so coloured print (Lidl's blue
     *   discount lines, red prices) becomes as dark as black print while white paper stays
     *   white. A plain grey conversion leaves blue text a medium grey.
     * - Each pixel is compared with the average of its surroundings (a window about two text
     *   lines high), not with one global threshold, so shadows, uneven light and a grey
     *   photo background don't turn whole areas black or wipe out text.
     * - A pixel only becomes black if it is clearly darker than its surroundings, so flat
     *   areas (paper, background) stay white instead of turning into noise.
     */
    fun binarize(argb: IntArray, width: Int, height: Int): IntArray {
        require(argb.size == width * height)
        val ink = IntArray(argb.size) { i ->
            val p = argb[i]
            min((p shr 16) and 0xFF, min((p shr 8) and 0xFF, p and 0xFF))
        }
        val radius = max(8, max(width, 1) / 24)
        // Written in place over the means to keep memory low on big photos
        val out = boxMean(ink, width, height, radius)
        for (i in ink.indices) {
            val mean = out[i]
            val black = ink[i] < mean * (1 - DARKER_BY) && mean - ink[i] > MIN_CONTRAST
            out[i] = if (black) BLACK else WHITE
        }
        return out
    }

    private const val DARKER_BY = 0.15
    private const val MIN_CONTRAST = 20
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Average of [values] in a (2r+1)² window around each pixel (clipped at the edges). */
    private fun boxMean(values: IntArray, width: Int, height: Int, r: Int): IntArray {
        // Horizontal running sums, then vertical running sums of those
        val rowSum = IntArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (x in 0..min(r, width - 1)) sum += values[row + x]
            for (x in 0 until width) {
                rowSum[row + x] = sum
                if (x + r + 1 < width) sum += values[row + x + r + 1]
                if (x - r >= 0) sum -= values[row + x - r]
            }
        }
        // Vertical pass, one column at a time, writing the means back into rowSum
        val column = IntArray(height)
        for (x in 0 until width) {
            for (y in 0 until height) column[y] = rowSum[y * width + x]
            val cols = min(x + r, width - 1) - max(x - r, 0) + 1
            var sum = 0
            for (y in 0..min(r, height - 1)) sum += column[y]
            for (y in 0 until height) {
                val rows = min(y + r, height - 1) - max(y - r, 0) + 1
                rowSum[y * width + x] = sum / (rows * cols)
                if (y + r + 1 < height) sum += column[y + r + 1]
                if (y - r >= 0) sum -= column[y - r]
            }
        }
        return rowSum
    }

    /** One word found by OCR, with its box in the original image's pixels. */
    data class Word(
        val text: String,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val confidence: Float,
        /** Which OCR pass found it: "bw" (black and white) or "orig". */
        val pass: String
    ) {
        val area get() = max(0f, right - left) * max(0f, bottom - top)
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    private fun overlap(a: Word, b: Word): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (w <= 0 || h <= 0) 0f else w * h
    }

    /**
     * Combines two OCR passes over the same receipt. Words of [primary] are kept; a word of
     * [secondary] is added where [primary] has nothing (a word that pass missed), and
     * replaces a [primary] word at the same place when it is clearly more confident
     * ("0,79" for a misread "O,79").
     */
    fun mergeWords(primary: List<Word>, secondary: List<Word>): List<Word> {
        val result = primary.toMutableList()
        for (word in secondary) {
            val overlapping = result.withIndex().filter { (_, p) ->
                val o = overlap(p, word)
                o > 0 && o > 0.3f * min(p.area, word.area)
            }
            when {
                overlapping.isEmpty() -> result += word
                overlapping.size == 1 -> {
                    val (i, p) = overlapping.single()
                    val iou = overlap(p, word) / (p.area + word.area - overlap(p, word))
                    if (iou > 0.5f && word.confidence > p.confidence + 0.15f) result[i] = word
                }
            }
        }
        return result
    }

    /** Scales a word found in an image scaled by [scale] back to original pixels. */
    fun Word.unscaled(scale: Float) = if (scale == 1f) this else copy(
        left = left / scale, top = top / scale, right = right / scale, bottom = bottom / scale
    )

    /** Size of [width] × [height] scaled by [scale], at least 1 pixel. */
    fun scaledSize(width: Int, height: Int, scale: Float) =
        max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}
