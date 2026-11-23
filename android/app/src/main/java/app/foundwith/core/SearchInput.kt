package app.foundwith.core

/** What the person typed into "Whose number is this?". */
sealed interface SearchQuery {
    data object Empty : SearchQuery
    /** Letters: search their own contacts and calls, on the phone. */
    data class Name(val text: String) : SearchQuery
    /**
     * A number: search everyone. Every kind is searched (Y, 23 Nov 2026). A 10-digit Indian number
     * is looked up as soon as it's typed ([auto]); a shorter one or another country's waits for the
     * Search button, so a number half typed never uses up a search.
     */
    data class Mobile(val e164: String, val auto: Boolean = true) : SearchQuery
    /** Digits, but not a number yet. */
    data object Incomplete : SearchQuery
}

object SearchInput {
    fun classify(input: String): SearchQuery {
        val text = input.trim()
        if (text.isEmpty()) return SearchQuery.Empty
        if (text.any { it.isLetter() }) return SearchQuery.Name(text)
        val e164 = PhoneNumbers.normalise(text) ?: return SearchQuery.Incomplete
        return SearchQuery.Mobile(e164, auto = PhoneNumbers.isFullIndian(e164))
    }

    /** Names that match: every word typed starts a word of the name ("ra ku" finds "Ravi Kumar"). */
    fun matches(name: String, query: String): Boolean {
        val words = name.lowercase().split(Regex("""[^\p{L}\p{M}\p{N}]+""")).filter { it.isNotEmpty() }
        val wanted = query.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return wanted.isNotEmpty() && wanted.all { w -> words.any { it.startsWith(w) } }
    }
}
