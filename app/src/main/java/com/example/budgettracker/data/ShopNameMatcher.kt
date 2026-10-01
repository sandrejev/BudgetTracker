package com.example.budgettracker.data

import kotlin.math.max

/**
 * Fuzzy matching of (often OCR-garbled) shop names against known shop names.
 *
 * Examples: "3Müller" → "Müller", "LGDL" → "LIDL", "Mueller Drogerie" → "Müller".
 *
 * Names are normalised before comparing: lower-cased, umlauts folded
 * (ü/ue → u, ß → ss) and everything except letters removed, so stray digits
 * and punctuation from OCR don't matter. Similarity is 1 − editDistance / longerLength.
 */
object ShopNameMatcher {

    /** Minimum similarity for an automatic match (one wrong letter in a 4-letter name). */
    const val AUTO_MATCH_THRESHOLD = 0.75

    /** Lower bar for showing a name as a suggestion while typing. */
    const val SUGGESTION_THRESHOLD = 0.5

    /** Names shorter than this (after normalising) must match exactly. */
    private const val MIN_FUZZY_LENGTH = 4

    fun normalize(name: String): String =
        name.lowercase()
            .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
            .replace("ae", "a").replace("oe", "o").replace("ue", "u")
            .filter { it in 'a'..'z' }

    /** Similarity in 0..1 between two raw names (1 = identical after normalising). */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (max(x.length, y.length) < MIN_FUZZY_LENGTH) return 0.0
        return 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
    }

    /**
     * Best score of [known] against [text], where [text] may be a whole OCR line
     * ("LIDL Dienstleistung GmbH"): the line, each word and each pair of adjacent
     * words are compared, and a known name contained in the line counts as a match.
     */
    fun score(text: String, known: String): Double {
        val k = normalize(known)
        if (k.isEmpty()) return 0.0
        if (k.length >= MIN_FUZZY_LENGTH && normalize(text).contains(k)) return 0.95
        val words = text.split(Regex("\\s+")).filter { normalize(it).isNotEmpty() }
        val candidates = listOf(text) + words + words.zipWithNext { a, b -> "$a $b" }
        return candidates.maxOf { similarity(it, known) }
    }

    /**
     * Returns the known name that best matches any of [texts] (e.g. the first few
     * receipt header lines), or null if nothing scores at least [threshold].
     */
    fun bestMatch(
        texts: List<String>,
        knownNames: List<String>,
        threshold: Double = AUTO_MATCH_THRESHOLD
    ): String? =
        knownNames
            .map { name -> name to texts.maxOfOrNull { score(it, name) }.orZero() }
            .filter { (_, s) -> s >= threshold }
            .maxByOrNull { (_, s) -> s }
            ?.first

    /**
     * Known names to suggest for what the user has typed so far, best first:
     * prefix matches, then substring matches, then fuzzy matches.
     * A blank query returns all names.
     */
    fun suggestions(query: String, knownNames: List<String>, limit: Int = 6): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return knownNames.take(limit)
        val nq = normalize(q)
        return knownNames
            .map { name ->
                val n = normalize(name)
                val rank = when {
                    name.startsWith(q, ignoreCase = true) || (nq.isNotEmpty() && n.startsWith(nq)) -> 3.0
                    name.contains(q, ignoreCase = true) || (nq.isNotEmpty() && n.contains(nq)) -> 2.0
                    else -> similarity(q, name)
                }
                name to rank
            }
            .filter { (_, rank) -> rank >= SUGGESTION_THRESHOLD }
            .sortedByDescending { (_, rank) -> rank }
            .take(limit)
            .map { it.first }
    }

    private fun Double?.orZero() = this ?: 0.0

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
