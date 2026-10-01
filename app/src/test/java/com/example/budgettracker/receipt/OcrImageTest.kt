package com.example.budgettracker.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class OcrImageTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun blackShare(bw: IntArray, width: Int, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (bw[y * width + x] == black) n++
        return n.toDouble() / ((x1 - x0) * (y1 - y0))
    }

    @Test
    fun lidlBlueDiscountTextBecomesBlackAndPaperWhite() {
        val img = ImageIO.read(File("src/androidTest/assets/receipts/lidl_3.png"))
        val w = img.width
        val h = img.height
        val bw = OcrImage.binarize(img.getRGB(0, 0, w, h, null, 0, w), w, h)
        System.getenv("OCR_DEBUG_DIR")?.let { dir ->
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            out.setRGB(0, 0, w, h, bw, 0, w)
            ImageIO.write(out, "png", File(dir, "lidl_3_bw.png"))
        }
        // "Lidl Plus Rabatt" (blue) and "-0,59" (blue) have ink; the gap between them is paper
        assertTrue(blackShare(bw, w, 170, 545, 550, 590) > 0.10)
        assertTrue(blackShare(bw, w, 855, 970, 550, 590) > 0.10)
        assertEquals(0.0, blackShare(bw, w, 600, 820, 550, 590), 0.0)
        // Black print still black: "Kohlrabi"
        assertTrue(blackShare(bw, w, 50, 240, 505, 545) > 0.10)
    }

    @Test
    fun unevenLightingKeepsTextAndClearsBackground() {
        // Paper fading from white to dark grey (a shadow), with dark and blue "text" bars
        val w = 400
        val h = 200
        val px = IntArray(w * h) { i ->
            val x = i % w
            val v = 255 - x * 140 / w
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        fun bar(x0: Int, color: Int) {
            for (y in 90 until 110) for (x in x0 until x0 + 6) px[y * w + x] = color
        }
        bar(30, 0xFF202020.toInt())
        bar(200, 0xFF1E50B4.toInt())  // blue
        bar(360, 0xFF404040.toInt())  // dark text in the shadow
        val bw = OcrImage.binarize(px, w, h)
        assertEquals(1.0, blackShare(bw, w, 30, 36, 90, 110), 0.0)
        assertEquals(1.0, blackShare(bw, w, 200, 206, 90, 110), 0.0)
        assertEquals(1.0, blackShare(bw, w, 360, 366, 90, 110), 0.0)
        // The shaded paper itself is not ink
        assertEquals(0.0, blackShare(bw, w, 0, w, 0, 60), 0.0)
        assertEquals(0.0, blackShare(bw, w, 0, w, 150, h), 0.0)
        assertEquals(white, bw[0])
    }

    @Test
    fun scaleBringsImagesIntoRange() {
        assertEquals(1600f / 1080, OcrImage.scaleFor(1080, 1530), 0.001f)
        assertEquals(1f, OcrImage.scaleFor(2000, 2400), 0f)
        assertTrue(OcrImage.scaleFor(3000, 4000) < 1f)
        // A very long, narrow receipt isn't scaled past the pixel limit
        val s = OcrImage.scaleFor(800, 9000)
        assertTrue(800 * s * 9000 * s <= OcrImage.MAX_PIXELS * 1.01)
    }

    @Test
    fun mergeAddsMissedWordsAndKeepsOrReplacesOverlapping() {
        fun word(t: String, l: Float, c: Float, pass: String) = OcrImage.Word(t, l, 100f, l + 60f, 130f, c, pass)
        val bw = listOf(word("Kohlrabi", 50f, 0.9f, "bw"), word("O,79", 850f, 0.5f, "bw"))
        val orig = listOf(
            word("Kohlrabi", 52f, 0.95f, "orig"),   // same word, similar confidence: kept from bw
            word("0,79", 851f, 0.9f, "orig"),       // clearly more confident: replaces "O,79"
            word("A", 930f, 0.8f, "orig")           // missed by bw: added
        )
        val merged = OcrImage.mergeWords(bw, orig)
        assertEquals(listOf("Kohlrabi", "0,79", "A"), merged.map { it.text })
        assertEquals(listOf("bw", "orig", "orig"), merged.map { it.pass })
    }
}
