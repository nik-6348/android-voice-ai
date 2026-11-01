package com.nik.voiceassistant.tools

/**
 * Tier-1 screen verifier: matches the model's `expect` string against
 * the current accessibility element list without network I/O (~0–5 ms).
 *
 * Returns a [VerifyResult] when the match is conclusive (yes or no),
 * or null when elements are empty or the match is inconclusive (tier-2
 * vision is needed).
 */
object ElementVerifier {

    /** Words that indicate an error state on screen. */
    private val ERROR_MARKERS = listOf(
        "failed", "error", "couldn't", "could not", "unable", "not sent",
        "unsuccessful", "denied", "blocked", "rejected", "invalid"
    )

    /**
     * @param expect  What the model expects to be true after its action.
     * @param elements Accessibility element strings from the current screen.
     * @param app     Foreground app package name.
     * @return [VerifyResult] when conclusive, null when inconclusive.
     */
    fun check(expect: String, elements: List<String>, app: String): VerifyResult? {
        if (elements.isEmpty()) return null

        val elementsText = elements.joinToString(" ").lowercase()
        val expectLower = expect.lowercase()

        // --- Error detection: any error marker in elements → verified=no ---
        val errorMarker = ERROR_MARKERS.firstOrNull { marker -> elementsText.contains(marker) }
        if (errorMarker != null) {
            val matchingElement = elements.firstOrNull { it.lowercase().contains(errorMarker) } ?: ""
            return VerifyResult(
                verified = "no",
                via = "elements",
                evidence = "Error detected on screen: \"$matchingElement\"",
                blocker = "error_toast",
                stepDone = false,
                nextAction = null,
                elementRefs = listOfNotNull(matchingElement.ifBlank { null })
            )
        }

        // --- Keyword scoring ---
        // Extract meaningful keywords from expect (words > 3 chars, ignoring common words)
        val stopWords = setOf("that", "this", "with", "from", "have", "will", "been", "open", "visible", "shown", "screen")
        val keywords = expectLower
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 3 && it !in stopWords }

        if (keywords.isEmpty()) return null

        val matched = keywords.filter { kw -> elementsText.contains(kw) }
        val score = matched.size

        // Check for exact 3-word phrase match from expect
        val words = expectLower.split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        val hasExactPhrase = words.size >= 3 && (0..words.size - 3).any { i ->
            val phrase = words.subList(i, i + 3).joinToString(" ")
            elementsText.contains(phrase)
        }

        // Significant keywords are long (>5 chars) and thus more discriminating
        val significantKeywords = keywords.filter { it.length > 5 }
        val matchedSignificant = significantKeywords.filter { kw -> elementsText.contains(kw) }

        val isMatch = hasExactPhrase
            || matchedSignificant.size >= 2
            // Single strong keyword fully matched: e.g. "Settings" in "Settings screen visible"
            || (significantKeywords.size == 1 && matchedSignificant.size == 1)

        return if (isMatch) {
            val allMatched = matched + matchedSignificant
            val refs = elements.filter { el ->
                allMatched.any { kw -> el.lowercase().contains(kw) }
            }.take(3)
            VerifyResult(
                verified = "yes",
                via = "elements",
                evidence = "Found ${matchedSignificant.size} significant keyword(s) in elements: ${matchedSignificant.take(3)}",
                blocker = null,
                stepDone = true,
                nextAction = null,
                elementRefs = refs
            )
        } else {
            null  // inconclusive: need vision (tier-2)
        }
    }
}
