package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CallGroupsTest {
    private val t = 1_764_170_000_000L          // 26 Nov 2026, evening IST
    private fun c(id: Long, number: String?, type: CallType, at: Long, duration: Long = 0) = CallEvent(id, number, type, at, duration = duration)

    @Test
    fun repeatCallsFromOneNumberAreOneRowWithACount() {
        // Y's call log, 26 Nov 2026: two missed calls from Shankar at 9:06 pm were two rows.
        val calls = listOf(
            c(5, "9845011111", CallType.MISSED, t),
            c(4, "9845011111", CallType.MISSED, t - 40_000),
            c(3, "6379340981", CallType.OUTGOING, t - 9_000_000, duration = 15),
            c(2, "9492295309", CallType.BLOCKED, t - 9_300_000),
            c(1, "9845011111", CallType.MISSED, t - 9_400_000),       // not next to the others: its own row
        )
        val groups = CallGroups.group(calls)
        assertEquals(listOf(2, 1, 1, 1), groups.map { it.count })
        assertEquals(listOf(5L, 3L, 2L, 1L), groups.map { it.latest.id })
        assertEquals(listOf(5L, 4L), groups[0].ids)
    }

    @Test
    fun differentKindsAndHiddenNumbersStaySeparate() {
        val calls = listOf(
            c(4, "9845011111", CallType.OUTGOING, t, duration = 30),       // you called back
            c(3, "9845011111", CallType.MISSED, t - 60_000),
            c(2, null, CallType.MISSED, t - 120_000),
            c(1, null, CallType.MISSED, t - 180_000),
        )
        assertEquals(listOf(1, 1, 1, 1), CallGroups.group(calls).map { it.count })
    }

    @Test
    fun theSameCallWrittenTwiceIsOneCall() {
        // Some dual-SIM phones write a call twice.
        val calls = listOf(c(2, "9845011111", CallType.MISSED, t), c(1, "9845011111", CallType.MISSED, t - 1_000))
        val groups = CallGroups.group(calls)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].count)
    }

    @Test
    fun aRepeatOnAnotherDayIsAnotherRow() {
        val calls = listOf(c(2, "9845011111", CallType.MISSED, t), c(1, "9845011111", CallType.MISSED, t - 24 * 3_600_000L))
        assertEquals(2, CallGroups.group(calls).size)
    }
}
