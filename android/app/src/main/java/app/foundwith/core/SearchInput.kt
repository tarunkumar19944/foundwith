package app.foundwith.core

/** What the person typed into "Whose number is this?". */
sealed interface SearchQuery {
    data object Empty : SearchQuery
    /** Letters: search their own contacts and calls, on the phone. */
    data class Name(val text: String) : SearchQuery
    /** A full Indian mobile: search everyone. */
    data class Mobile(val e164: String) : SearchQuery
    /** Digits, but not a full number yet. */
    data object Incomplete : SearchQuery
    /** A full number that isn't an Indian mobile (a landline, a short code). Search is for mobiles. */
    data object NotMobile : SearchQuery
}

object SearchInput {
    fun classify(input: String): SearchQuery {
        val text = input.trim()
        if (text.isEmpty()) return SearchQuery.Empty
        if (text.any { it.isLetter() }) return SearchQuery.Name(text)
        val e164 = PhoneNumbers.normalise(text)
        if (e164 != null && PhoneNumbers.isIndianMobile(e164)) return SearchQuery.Mobile(e164)
        val digits = PhoneNumbers.clean(text)?.trimStart('+')?.length ?: text.count { it.isDigit() }
        return if (digits < 10) SearchQuery.Incomplete else SearchQuery.NotMobile
    }

    /** Names that match: every word typed starts a word of the name ("ra ku" finds "Ravi Kumar"). */
    fun matches(name: String, query: String): Boolean {
        val words = name.lowercase().split(Regex("""[^\p{L}\p{M}\p{N}]+""")).filter { it.isNotEmpty() }
        val wanted = query.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return wanted.isNotEmpty() && wanted.all { w -> words.any { it.startsWith(w) } }
    }
}
