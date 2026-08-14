package net.mhanak.yama.util

/**
 * Lightweight fuzzy matcher for the global search screen. Pure Kotlin (commonMain), no dependencies,
 * so it works identically on Android and JVM desktop.
 *
 * The query is split on whitespace into tokens that must *all* match (AND) — so "daft punk" matches
 * "Daft Punk" and also "Punk, Daft" where a plain subsequence would fail. Each token scores against
 * the target with two tiers, best wins:
 *  - a contiguous substring match (the common case — typing the start of a name), ranked by how early
 *    it appears and boosted at a prefix / word boundary;
 *  - failing that, an fzf-style subsequence match with bonuses for consecutive characters and matches
 *    that land on a word boundary — this is what gives typo/gap tolerance ("rndm acs" → "Random Access").
 *
 * A non-null result is a score where higher = better; null means "no match" for at least one token.
 */
fun fuzzyScore(query: String, target: String): Int? {
    if (query.isBlank()) return 0
    val t = target.lowercase()
    if (t.isEmpty()) return null
    val tokens = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    var total = 0
    for (token in tokens) {
        total += tokenScore(token, t) ?: return null
    }
    return total
}

/** Score a single already-lowercased [token] against an already-lowercased [target]. */
private fun tokenScore(token: String, target: String): Int? {
    // Tier 1: contiguous substring. Earlier occurrence ranks higher; prefix and word-boundary starts
    // get a boost, and a longer contiguous run beats a short one.
    val idx = target.indexOf(token)
    if (idx >= 0) {
        var score = 1000 - idx + token.length * 4
        score += when {
            idx == 0 -> 400
            isBoundary(target[idx - 1]) -> 200
            else -> 0
        }
        return score
    }

    // Tier 2: subsequence. Greedily match every token char in order; bail if any is missing.
    var qi = 0
    var score = 0
    var prevMatch = -2
    for (ti in target.indices) {
        if (qi >= token.length) break
        if (target[ti] == token[qi]) {
            var s = 10
            if (ti == prevMatch + 1) s += 15                     // consecutive run
            if (ti == 0 || isBoundary(target[ti - 1])) s += 20   // lands on a word start
            score += s
            prevMatch = ti
            qi++
        }
    }
    if (qi < token.length) return null
    // Prefer tighter matches: penalise a much longer target that only sparsely contains the token.
    return score - ((target.length - token.length).coerceAtLeast(0) / 4)
}

/** Characters that begin a new "word", so a match right after one reads as a meaningful start. */
private fun isBoundary(c: Char): Boolean =
    c == ' ' || c == '-' || c == '_' || c == '(' || c == '/' || c == '.' || c == ',' || c == '&' || c == '\''

/**
 * Filter [this] to items whose [key] (or any of [extraKeys], e.g. an album's artist) fuzzily matches
 * [query], ordered best-match first. A blank query yields an empty list (an empty search box shows
 * nothing). Ties keep the input order — Kotlin's [sortedByDescending] is stable.
 */
fun <T> List<T>.fuzzyFilterSort(
    query: String,
    key: (T) -> String,
    extraKeys: (T) -> List<String> = { emptyList() },
): List<T> {
    if (query.isBlank()) return emptyList()
    return mapNotNull { item ->
        val best = (listOf(key(item)) + extraKeys(item))
            .mapNotNull { fuzzyScore(query, it) }
            .maxOrNull()
        best?.let { item to it }
    }.sortedByDescending { it.second }.map { it.first }
}
