package app.foundwith.core

/** A number on the phone that more people have than when the person last looked. */
data class CountChange(val ref: String, val before: Int, val now: Int, val at: Long) {
    val delta: Int get() = now - before
}

/** What the phone remembers between refreshes: the last count seen per number, and recent changes. */
data class ChangeLog(val baseline: Map<String, Int> = emptyMap(), val changes: Map<String, CountChange> = emptyMap())

/**
 * The "what changed" part of Updates. Each refresh of the cards compares the new counts with the
 * baseline. A count that went up becomes (or extends) a change; a count that went down just moves
 * the baseline. Changes are kept for a week.
 */
object Changes {
    const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L

    /**
     * [first]: the first refresh after install or sign-in. Everything is new then, so nothing is
     * reported as a change; the counts only become the baseline.
     */
    fun update(log: ChangeLog, totals: Map<String, Int>, now: Long, first: Boolean = false): ChangeLog {
        if (first) return ChangeLog(totals, emptyMap())
        val changes = log.changes.filterValues { now - it.at <= KEEP_MS }.toMutableMap()
        for ((ref, total) in totals) {
            val before = log.baseline[ref] ?: continue          // a new card isn't "more people"
            val earlier = changes[ref]
            if (total > before) {
                changes[ref] = CountChange(ref, earlier?.before ?: before, total, now)
            } else if (earlier != null) {
                // Fewer people than last time: the change shrinks, or goes once nothing is left of it.
                if (total <= earlier.before) changes.remove(ref) else changes[ref] = earlier.copy(now = total)
            }
        }
        // Cards that disappeared (number deleted, owner opted out) take their change with them.
        changes.keys.retainAll(totals.keys)
        return ChangeLog(totals, changes)
    }

    /** Whether a change happened on the same day as [now] (for the green "Today" line). */
    fun isToday(change: CountChange, now: Long, startOfToday: Long): Boolean = change.at in startOfToday..now
}

/** One row in the Updates list. */
data class UpdateRow(val key: String, val at: Long, val isNew: Boolean, val isToday: Boolean)

object UpdatesFeed {
    /**
     * Updates are calls from numbers the person hasn't saved, and contacts whose count went up,
     * newest first. [isNew] (the green dot) is anything since they last opened the app; it clears
     * by itself. [isToday] splits the list into Today and Earlier.
     */
    fun rows(callKeys: List<Pair<String, Long>>, changeKeys: List<Pair<String, Long>>, lastOpenedAt: Long,
             startOfToday: Long): List<UpdateRow> {
        val newest = HashMap<String, Long>()
        for ((key, at) in callKeys + changeKeys) newest[key] = maxOf(at, newest[key] ?: Long.MIN_VALUE)
        return newest.map { (key, at) -> UpdateRow(key, at, at > lastOpenedAt, at >= startOfToday) }
            .sortedByDescending { it.at }
    }
}

/** Contact entries in the sync snapshot carry the name they were saved under. */
object ContactEntry {
    private const val SEP = '\u001F'

    fun encode(raw: String, name: String?): String {
        val clean = name?.trim()?.replace(SEP, ' ')
        return if (clean.isNullOrEmpty() || Naming.looksLikeNumber(clean)) raw else raw + SEP + clean
    }

    fun raw(entry: String): String = entry.substringBefore(SEP)

    fun name(entry: String): String? = entry.substringAfter(SEP, "").ifEmpty { null }

    /** For [SnapshotDiff]: two entries are the same number when their raw parts normalise alike. */
    val normaliser: (String) -> String? = { PhoneNumbers.normalise(raw(it)) }
}

object SyncPolicy {
    /** The daily background run sends only changes; the whole address book goes once a week. */
    const val FULL_EVERY_MS = 7 * 24 * 60 * 60 * 1000L

    fun fullDue(lastFullAt: Long, now: Long): Boolean = lastFullAt <= 0L || now - lastFullAt >= FULL_EVERY_MS
}
