package com.example.budgettracker.receipt

import org.json.JSONObject

/** A common name and category suggested for one receipt item. */
data class NameSuggestion(val name: String, val category: String? = null)

/** LLM prompt and answer parsing for converting receipt item texts to common names. */
object CommonNamePrompt {

    fun build(itemNames: List<String>, knownNames: List<String>, categories: List<String>): String {
        val numbered = itemNames.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")
        val known = knownNames.joinToString(", ").ifEmpty { "none yet" }
        return """
You are a grocery receipt item normalizer. Receipt texts are often abbreviated German (e.g. "BIO VOLLM. 3,8%").

For each numbered receipt item below give:
- "name": a short human-readable common name in English, lowercase, 1–4 words (e.g. "whole milk", "sourdough bread", "plums"). Leave out brands, sizes and percentages. PREFER reusing one of the known common names when it clearly fits.
- "category": exactly one of the categories listed below.

Known common names: $known

Categories: ${categories.joinToString(", ")}

Return ONLY a JSON object mapping each item number (as a string) to an object, e.g.
{"1": {"name": "whole milk", "category": "Dairy & eggs"}, "2": {"name": "plums", "category": "Fresh fruits"}}

Items:
$numbered
""".trim()
    }

    /**
     * Parses the LLM's JSON answer into 1-based item index → suggestion. Also accepts the
     * older answer format {"1": "whole milk"}. A category is kept only if it matches one
     * of [categories] (case-insensitive) and is returned in that category's spelling.
     */
    fun parse(json: String, count: Int, categories: List<String>): Map<Int, NameSuggestion> {
        val obj = JSONObject(json)
        val result = mutableMapOf<Int, NameSuggestion>()
        for (i in 1..count) {
            val value = obj.opt("$i") ?: continue
            val (name, category) = when (value) {
                is JSONObject -> value.optString("name") to value.optString("category")
                else -> value.toString() to ""
            }
            val cleanName = name.trim()
            if (cleanName.isEmpty() || cleanName == "null") continue
            val matchedCategory = categories.firstOrNull { it.equals(category.trim(), ignoreCase = true) }
            result[i] = NameSuggestion(cleanName, matchedCategory)
        }
        return result
    }
}
