package app.foundwith.core

/** One thing that changed since the last refresh, for a "changes" notification. */
sealed class ChangeItem {
    data class MineUp(val delta: Int, val total: Int = 0) : ChangeItem()
    data class CountUp(val ref: String, val delta: Int, val total: Int) : ChangeItem()
    data class NowKnown(val ref: String, val ids: List<String>) : ChangeItem()
}

data class ChangeNote(val title: String, val text: String, val count: Int)

/**
 * "A notification for every change" (Y, 23 Nov 2026): after a background refresh, each number
 * that changed since the last one gets its own notification ([each]). Friends who join have their
 * own notification and are not repeated here. Numbers already told about after a call are skipped
 * through [skip]. ([build], the merged note of 22 Nov, is kept for its tests and wording.)
 */
object ChangeNotes {
    fun items(old: ChangeLog?, updated: ChangeLog, mineRef: String?, skip: Set<String> = emptySet()): List<ChangeItem> {
        if (old == null) return emptyList()
        val out = ArrayList<ChangeItem>()
        for ((ref, change) in updated.changes) {
            if (ref in skip && ref != mineRef) continue
            val seen = old.changes[ref]?.now
            if (seen != null && change.now <= seen) continue                     // nothing new since last time
            val delta = change.now - (seen ?: change.before)
            if (delta <= 0) continue
            out += if (ref == mineRef) ChangeItem.MineUp(delta, change.now) else ChangeItem.CountUp(ref, delta, change.now)
        }
        for ((ref, gain) in updated.gains) {
            if (ref in skip) continue
            val seen = old.gains[ref]?.ids.orEmpty().toSet()
            val fresh = gain.ids.filter { it !in seen }
            if (fresh.isNotEmpty()) out += ChangeItem.NowKnown(ref, fresh)
        }
        return out
    }

    /** One change, for its own notification: [ref] is null for your own number. */
    data class One(val ref: String?, val title: String, val text: String)

    /**
     * One notification per change: "Raju Plumber · 2 more people have this number · now 14",
     * "+91 90080 11223 · Now known to Kiran Sir", "1 more person has your number · Now 23".
     * Two changes to the same number become one (its count, and who now knows it).
     */
    fun each(items: List<ChangeItem>, label: (String) -> String, holders: (List<String>) -> List<String>): List<One> {
        val out = LinkedHashMap<String?, One>()
        for (item in items) {
            when (item) {
                is ChangeItem.MineUp -> out[null] = One(null, HolderText.mineUp(item.delta),
                    if (item.total > 0) "Now ${HolderText.grouped(item.total)}" else "")
                is ChangeItem.CountUp -> {
                    val text = HolderText.change(item.total, item.delta)
                    val had = out[item.ref]
                    out[item.ref] = One(item.ref, label(item.ref), if (had == null) text else "$text · ${had.text}")
                }
                is ChangeItem.NowKnown -> {
                    val names = holders(item.ids)
                    val text = if (names.isEmpty()) "Now known to someone you know"
                        else "Now known to ${names.take(2).joinToString(" and ")}" + if (names.size > 2) " and ${names.size - 2} more" else ""
                    val had = out[item.ref]
                    out[item.ref] = One(item.ref, label(item.ref), if (had == null) text else "$text · ${had.text}")
                }
            }
        }
        return out.values.toList()
    }

    /**
     * [label]: the saved name of a number, or its shown number. [saved]: whether it is in the contacts.
     * [holders]: names for holder ids (people the viewer knows).
     */
    fun build(items: List<ChangeItem>, label: (String) -> String, saved: (String) -> Boolean, holders: (List<String>) -> List<String>): ChangeNote? {
        if (items.isEmpty()) return null
        val lines = items.map { item ->
            when (item) {
                is ChangeItem.MineUp -> HolderText.mineUp(item.delta)
                is ChangeItem.CountUp -> label(item.ref).let { name ->
                    if (saved(item.ref)) HolderText.changeFor(name, item.total, item.delta) else "$name: ${HolderText.change(item.total, item.delta)}"
                }
                is ChangeItem.NowKnown -> {
                    val names = holders(item.ids)
                    if (names.isEmpty()) "${label(item.ref)} is now known to someone you know"
                    else "${label(item.ref)} is now known to ${names.take(2).joinToString(" and ")}" + if (names.size > 2) " and ${names.size - 2} more" else ""
                }
            }
        }
        if (items.size == 1) return ChangeNote(lines[0], "", 1)
        return ChangeNote("${items.size} changes on WhoHas", lines.take(2).joinToString(". ") + if (items.size > 2) ". And ${items.size - 2} more." else ".", items.size)
    }
}
