package app.foundwith.core

/**
 * What last happened to a number in Updates. When two happen at the same moment, the earlier one
 * in this list names the row.
 */
enum class UpdateWhat { NOW_KNOWN, JOINED, COUNT_UP, CALL }

data class UpdateEvent(val e164: String, val at: Long, val what: UpdateWhat)

/**
 * One row of Updates: a number, its latest event ([latest], null when nothing happened to it
 * lately) and its latest change that wasn't a call ([change]), which says what changed.
 */
data class UpdateItem(val e164: String, val latest: UpdateEvent?, val change: UpdateEvent?) {
    val at: Long? get() = latest?.at
}

/**
 * Updates (Y, 23 Nov 2026): one list of numbers from your contacts and calls, each once, the one
 * that changed most recently on top, so nobody has to look through Call log and Contacts for what's new.
 */
object UpdatesOrder {
    /**
     * [found] is every number to show, already in the order for numbers with nothing recent (people
     * you know first, then the most people). Events for numbers not in [found] are left out.
     */
    fun order(found: List<String>, events: List<UpdateEvent>): List<UpdateItem> {
        val shown = found.toHashSet()
        val latest = HashMap<String, UpdateEvent>()
        val change = HashMap<String, UpdateEvent>()
        for (e in events) {
            if (e.e164 !in shown) continue
            if (later(e, latest[e.e164])) latest[e.e164] = e
            if (e.what != UpdateWhat.CALL && later(e, change[e.e164])) change[e.e164] = e
        }
        val recent = latest.values
            .sortedWith(compareByDescending<UpdateEvent> { it.at }.thenBy { it.what.ordinal }.thenBy { it.e164 })
            .map { UpdateItem(it.e164, it, change[it.e164]) }
        val rest = found.asSequence().distinct().filter { it !in latest }.map { UpdateItem(it, null, null) }
        return recent + rest
    }

    /**
     * Rows with something after [seenAt] that hasn't been seen on the number's own card since
     * ([seen]: number -> when its card was last opened): the count on the Updates tab, the rows
     * under New, and the marks in Call log and Contacts (Y, 23 Nov 2026). Before Updates was ever
     * looked at ([seenAt] 0) nothing is marked new, or every call would be.
     */
    fun newSince(items: List<UpdateItem>, seenAt: Long, seen: Map<String, Long> = emptyMap()): List<UpdateItem> =
        if (seenAt <= 0L) emptyList() else items.filter { isNew(it, seenAt, seen[it.e164]) }

    fun isNew(item: UpdateItem, seenAt: Long, seenNumber: Long?): Boolean =
        seenAt > 0L && (item.at ?: Long.MIN_VALUE) > maxOf(seenAt, seenNumber ?: 0L)

    private fun later(e: UpdateEvent, than: UpdateEvent?): Boolean =
        than == null || e.at > than.at || (e.at == than.at && e.what.ordinal < than.what.ordinal)
}
