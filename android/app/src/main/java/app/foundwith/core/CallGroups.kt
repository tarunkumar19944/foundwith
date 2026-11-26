package app.foundwith.core

import java.time.Instant
import java.time.ZoneId

/**
 * The call log groups calls like the phone's own call log (Y, 26 Nov 2026, "call logs are not
 * grouping"): calls from the same number, one after another, of the same kind and on the same day,
 * are one row with a count. The same call written twice (some dual-SIM phones) is one call.
 */
data class CallGroup(val latest: CallEvent, val count: Int, val ids: List<Long>)

object CallGroups {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** Two entries within this of each other, same number, kind and length, are the same call. */
    private const val SAME_CALL_MS = 3_000L

    /** [calls] in any order; the groups come newest first. */
    fun group(calls: List<CallEvent>): List<CallGroup> {
        val out = ArrayList<CallGroup>()
        var current: MutableList<CallEvent>? = null
        var count = 0
        for (call in calls.sortedByDescending { it.at }) {
            val head = current?.first()
            if (head != null && sameRow(head, call)) {
                val last = current!!.last()
                // The same call written twice: one call, not two.
                if (!(last.at - call.at <= SAME_CALL_MS && last.duration == call.duration)) count++
                current += call
                continue
            }
            if (current != null) out += CallGroup(current.first(), count, current.map { it.id })
            current = mutableListOf(call)
            count = 1
        }
        if (current != null) out += CallGroup(current.first(), count, current.map { it.id })
        return out
    }

    private fun sameRow(a: CallEvent, b: CallEvent): Boolean {
        if (a.isHidden || b.isHidden) return false        // two private numbers may be different people
        val number = a.e164 ?: return false
        return number == b.e164 && kind(a) == kind(b) && day(a.at) == day(b.at)
    }

    /** Missed and rejected are both "missed"; a dialled call that wasn't answered is its own kind. */
    private fun kind(c: CallEvent): String = when (c.type) {
        CallType.MISSED, CallType.REJECTED -> "missed"
        CallType.OUTGOING -> if (c.duration > 0) "out" else "out-none"
        else -> c.type.name
    }

    private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(IST).toLocalDate()
}
