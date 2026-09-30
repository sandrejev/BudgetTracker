package com.example.budgettracker.receipt

import org.json.JSONArray
import org.json.JSONObject

/** A single token on a receipt line: display text + normalized x position (0.0–1.0). */
data class L0Token(val text: String, val x: Float)

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
    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        put("page_w", pageWidth)
        put("sections", JSONArray(sections.map { sec ->
            JSONObject().apply {
                put("type", sec.type)
                put("lines", JSONArray(sec.lines.map { line ->
                    when (line) {
                        is L0Line.Separator -> JSONObject().put("sep", true)
                        is L0Line.Tokens -> JSONArray(line.tokens.map { tok ->
                            JSONObject().put("t", tok.text).put("x", tok.x.toDouble())
                        })
                    }
                }))
            }
        }))
    }

    fun toJsonString(indent: Int = 2): String = toJson().toString(indent)

    companion object {
        fun fromJsonString(json: String): Level0Doc = fromJson(JSONObject(json))

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
                                L0Token(t.optString("t"), t.optDouble("x", 0.5).toFloat())
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
