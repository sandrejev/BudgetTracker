package com.example.budgettracker.receipt

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single token on a receipt line: display text + normalized x position (0.0–1.0).
 * For photos also the OCR [confidence] (0–1), kept for debugging (Copy OCR data); the
 * processors don't use it.
 */
data class L0Token(val text: String, val x: Float, val confidence: Float? = null)

/** One parsed line in a section — either a list of tokens or a visual separator. */
sealed class L0Line {
    data class Tokens(val tokens: List<L0Token>) : L0Line()
    object Separator : L0Line()
}

/** A logical section of the receipt. */
data class L0Section(
    val type: String,   // "header" | "body" | "vat" | "footer"
    val lines: List<L0Line>
)

/** Root Level 0 document produced from a PNG or PDF receipt. */
data class Level0Doc(
    val source: String,    // "png" | "pdf"
    val pageWidth: Int,
    val sections: List<L0Section>
) {
    fun toJson(): JSONObject = JSONObject(toJsonString())

    /**
     * Readable but compact JSON: one line per token (`{"t": "Kohlrabi", "x": 0.134, "c": 0.95}`), positions rounded to 3 decimals and confidences to 2.
     */
    fun toJsonString(): String = buildString {
        append("{\n")
        append("  \"source\": ").append(JSONObject.quote(source)).append(",\n")
        append("  \"page_w\": ").append(pageWidth).append(",\n")
        append("  \"sections\": [")
        sections.forEachIndexed { si, sec ->
            append(if (si == 0) "\n" else ",\n")
            append("    {\n")
            append("      \"type\": ").append(JSONObject.quote(sec.type)).append(",\n")
            append("      \"lines\": [")
            sec.lines.forEachIndexed { li, line ->
                append(if (li == 0) "\n" else ",\n")
                when (line) {
                    is L0Line.Separator -> append("        {\"sep\": true}")
                    is L0Line.Tokens -> {
                        append("        [\n")
                        append(line.tokens.joinToString(",\n") { "          " + tokenJson(it) })
                        append("\n        ]")
                    }
                }
            }
            append(if (sec.lines.isEmpty()) "]\n" else "\n      ]\n")
            append("    }")
        }
        append(if (sections.isEmpty()) "]\n" else "\n  ]\n")
        append("}")
    }

    private fun tokenJson(tok: L0Token): String = buildString {
        append("{\"t\": ").append(JSONObject.quote(tok.text))
        append(", \"x\": ").append(decimal(tok.x, 3))
        tok.confidence?.let { append(", \"c\": ").append(decimal(it, 2)) }
        append("}")
    }

    companion object {
        fun fromJsonString(json: String): Level0Doc = fromJson(JSONObject(json))

        /** [json] (e.g. stored in an older, longer format) re-written as [toJsonString]; unchanged if it can't be read. */
        fun reformat(json: String): String = runCatching { fromJsonString(json).toJsonString() }.getOrDefault(json)

        /** [value] with at most [digits] decimals, without trailing zeros: 0.134, 0.5, 1. */
        private fun decimal(value: Float, digits: Int): String =
            java.math.BigDecimal(value.toDouble()).setScale(digits, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

        fun fromJson(json: JSONObject): Level0Doc {
            val source = json.optString("source", "unknown")
            val pageWidth = json.optInt("page_w", 0)
            val sectionsArr = json.optJSONArray("sections") ?: JSONArray()
            val sections = (0 until sectionsArr.length()).mapNotNull { i ->
                val sObj = sectionsArr.optJSONObject(i) ?: return@mapNotNull null
                val type = sObj.optString("type", "body")
                val linesArr = sObj.optJSONArray("lines") ?: JSONArray()
                val lines: List<L0Line> = (0 until linesArr.length()).mapNotNull { j ->
                    when (val el = linesArr.get(j)) {
                        is JSONObject -> if (el.optBoolean("sep")) L0Line.Separator else null
                        is JSONArray -> {
                            val tokens = (0 until el.length()).mapNotNull { k ->
                                val t = el.optJSONObject(k) ?: return@mapNotNull null
                                L0Token(
                                    t.optString("t"), t.optDouble("x", 0.5).toFloat(),
                                    t.optDouble("c").takeIf { !it.isNaN() }?.toFloat()
                                )
                            }
                            if (tokens.isEmpty()) null else L0Line.Tokens(tokens)
                        }
                        else -> null
                    }
                }
                L0Section(type, lines)
            }
            return Level0Doc(source, pageWidth, sections)
        }
    }
}
