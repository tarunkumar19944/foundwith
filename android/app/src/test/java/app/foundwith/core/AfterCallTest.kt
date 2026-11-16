package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterCallTest {
    private val now = 1_763_280_000_000L
    private fun call(id: Long, raw: String?, type: CallType, minutesAgo: Long = 1) = CallEvent(id, raw, type, now - minutesAgo * 60_000)
    private val noon = 12
    private val night = 22

    @Test
    fun aMissedUnsavedCallThatSomeoneHasMakesASound() {
        val note = AfterCallRules.decide(call(1, "77609 34418", CallType.MISSED),
            CallerInfo(null, total = 5, knownNames = listOf("Priya (Office)", "Ravi Kumar")), noon)
        assertEquals(Alert.SOUND, note.alert)
        assertEquals("Missed call: +91 77609 34418", note.title)
        assertEquals("Known to Priya (Office), Ravi Kumar and 3 more", note.text)
    }

    @Test
    fun quietHoursNeverMakeASound() {
        val info = CallerInfo(null, total = 5)
        assertEquals(Alert.SILENT, AfterCallRules.decide(call(1, "7760934418", CallType.MISSED), info, night).alert)
        assertEquals(Alert.SILENT, AfterCallRules.decide(call(1, "7760934418", CallType.MISSED), info, 8).alert)
        assertEquals(Alert.SOUND, AfterCallRules.decide(call(1, "7760934418", CallType.MISSED), info, 9).alert)
        assertTrue(AfterCallRules.isQuiet(21) && !AfterCallRules.isQuiet(20))
    }

    @Test
    fun answeredCallsAreSilent() {
        val note = AfterCallRules.decide(call(1, "7760934418", CallType.ANSWERED), CallerInfo(null, total = 4), noon)
        assertEquals(Alert.SILENT, note.alert)
        assertEquals("Call from +91 77609 34418", note.title)
        assertEquals("4 people on WhoHas have this number", note.text)
    }

    @Test
    fun aNamedNumberIsWorthTellingEvenIfNobodyElseHasIt() {
        val note = AfterCallRules.decide(call(1, "9901566420", CallType.MISSED), CallerInfo(null, total = 0, ownerName = "Deepa Rao"), noon)
        assertEquals(Alert.SOUND, note.alert)
        assertEquals("Their own name: Deepa Rao · No one on WhoHas has this number", note.text)
        val popular = AfterCallRules.decide(call(2, "8095122764", CallType.MISSED), CallerInfo(null, total = 9, popularName = "Mahesh Electricals"), noon)
        assertEquals("People know it as “Mahesh Electricals” · 9 people on WhoHas have this number", popular.text)
    }

    @Test
    fun nothingForCallsThatTellNothing() {
        val nobody = CallerInfo(null, total = 0)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "6362140987", CallType.MISSED), nobody, noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "7760934418", CallType.OUTGOING), CallerInfo(null, 5), noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, null, CallType.MISSED), CallerInfo(null, 5), noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "", CallType.MISSED), CallerInfo(null, 5), noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "7760934418", CallType.MISSED), CallerInfo(null, 5, blocked = true), noon).alert)
    }

    @Test
    fun aSavedContactOnlyWhenTheirListGrew() {
        val same = CallerInfo("Kiran Gowda", total = 12, previousTotal = 12)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "9900148823", CallType.ANSWERED), same, noon).alert)
        val unknownBefore = CallerInfo("Kiran Gowda", total = 12, previousTotal = null)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "9900148823", CallType.ANSWERED), unknownBefore, noon).alert)
        val grew = AfterCallRules.decide(call(1, "9900148823", CallType.ANSWERED), CallerInfo("Kiran Gowda", 12, previousTotal = 9), noon)
        assertEquals(AfterCallNote(Alert.SILENT, "Call from Kiran Gowda", "3 more people have Kiran Gowda's number · now 12", "+919900148823"), grew)
        val missed = AfterCallRules.decide(call(1, "9900148823", CallType.MISSED), CallerInfo("Kiran Gowda", 12, previousTotal = 9), noon)
        assertEquals(Alert.SILENT, missed.alert)
        assertEquals("Missed call: Kiran Gowda", missed.title)
    }

    @Test
    fun repeatCallsFoldIntoOneNotification() {
        val calls = listOf(call(1, "7760934418", CallType.MISSED, 20), call(2, "+917760934418", CallType.MISSED, 3),
            call(3, "9900148823", CallType.MISSED, 2), call(4, "7760934418", CallType.ANSWERED, 90))
        val plan = CallCatchUp.plan(calls, handled = emptySet(), now = now)
        assertEquals(listOf(2L, 3L), plan.toNotify.map { it.id })
        assertEquals(listOf(4L), plan.tooLate.map { it.id })
        assertEquals(2, CallCatchUp.repeats(calls[1], calls))
        val note = AfterCallRules.decide(calls[1], CallerInfo(null, 5), noon, repeats = 2)
        assertEquals("2 missed calls: +91 77609 34418", note.title)
    }

    @Test
    fun handledAndOutgoingCallsAreSkipped() {
        val calls = listOf(call(1, "7760934418", CallType.MISSED), call(2, "9900148823", CallType.OUTGOING),
            call(3, "9900148823", CallType.ANSWERED, 60L * 24 * 8))
        val plan = CallCatchUp.plan(calls, handled = setOf(1L), now = now)
        assertEquals(emptyList<Long>(), plan.toNotify.map { it.id })
        assertEquals(emptyList<Long>(), plan.tooLate.map { it.id })       // older than a week: ignored
    }

    @Test
    fun healthCountsCallsCheckedInTime() {
        val day = HealthDay("2026-11-16").seen(true).seen(false).seen(true)
        assertEquals(HealthDay("2026-11-16", callsSeen = 3, callsChecked = 2), day)
        assertTrue(HealthDay.needsFix(2) && !HealthDay.needsFix(1))
    }
}
