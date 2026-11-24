package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aNameAloneIsNotEnoughWhenNobodyHasTheNumber() {
        val note = AfterCallRules.decide(call(1, "9901566420", CallType.MISSED), CallerInfo(null, total = 0, ownerName = "Deepa Rao"), noon)
        assertEquals(Alert.NONE, note.alert)
        val popular = AfterCallRules.decide(call(2, "8095122764", CallType.MISSED), CallerInfo(null, total = 9, popularName = "Mahesh Electricals"), noon)
        assertEquals("People know it as “Mahesh Electricals” · 9 people on WhoHas have this number", popular.text)
        val own = AfterCallRules.decide(call(3, "9901566420", CallType.MISSED), CallerInfo(null, total = 3, ownerName = "Deepa Rao"), noon)
        assertEquals("Their own name: Deepa Rao · 3 people on WhoHas have this number", own.text)
    }

    @Test
    fun everyKindOfCallIsTold() {
        // Y, 24 Nov 2026: a blocked call from a 1600 number known to two people went untold.
        val known = CallerInfo(null, total = 2, knownNames = listOf("Vijaya Lakshmi", "Tarun Kumar"))
        val blocked = AfterCallRules.decide(call(1, "+911600113814", CallType.BLOCKED), known, noon)
        assertEquals(Alert.SILENT, blocked.alert)
        assertEquals("Blocked call: +91 16001 13814", blocked.title)
        assertEquals("Known to Vijaya Lakshmi and Tarun Kumar", blocked.text)
        assertEquals("Voicemail: +91 77609 34418", AfterCallRules.decide(call(2, "7760934418", CallType.VOICEMAIL), known, noon).title)
        assertEquals("Call from +91 77609 34418 · answered on another device",
            AfterCallRules.decide(call(3, "7760934418", CallType.ELSEWHERE), known, noon).title)
        assertEquals("Call: +91 77609 34418", AfterCallRules.decide(call(4, "7760934418", CallType.OTHER), known, noon).title)
        // Only missed and rejected calls ring with sound.
        assertEquals(Alert.SOUND, AfterCallRules.decide(call(5, "7760934418", CallType.MISSED), known, noon).alert)
        assertEquals(Alert.SILENT, AfterCallRules.decide(call(6, "7760934418", CallType.VOICEMAIL), known, noon).alert)
        // A saved contact whose count went up, blocked: told with its name.
        val contact = CallerInfo("Bank", total = 4, previousTotal = 2)
        assertEquals("Blocked call: Bank", AfterCallRules.decide(call(7, "7760934418", CallType.BLOCKED), contact, noon).title)
    }

    @Test
    fun nothingForCallsThatTellNothing() {
        val nobody = CallerInfo(null, total = 0)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "6362140987", CallType.MISSED), nobody, noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "6362140987", CallType.OUTGOING), nobody, noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, null, CallType.MISSED), CallerInfo(null, 5), noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "", CallType.MISSED), CallerInfo(null, 5), noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(1, "7760934418", CallType.MISSED), CallerInfo(null, 5, blocked = true), noon).alert)
    }

    @Test
    fun aDialledCallIsToldQuietlyAfterItEnds() {
        val note = AfterCallRules.decide(call(1, "9731120456", CallType.OUTGOING), CallerInfo(null, total = 2, knownNames = listOf("Priya", "Meena")), noon)
        assertEquals(AfterCallNote(Alert.SILENT, "You called +91 97311 20456", "Known to Priya and Meena", "+919731120456"), note)
        val night = AfterCallRules.decide(call(1, "9731120456", CallType.OUTGOING), CallerInfo(null, total = 2), 23)
        assertEquals(Alert.SILENT, night.alert)
        val saved = AfterCallRules.decide(call(1, "9900148823", CallType.OUTGOING), CallerInfo("Kiran Gowda", 12, previousTotal = 9), noon)
        assertEquals("You called Kiran Gowda", saved.title)
    }

    @Test
    fun aRepeatCallIsToldOnlyWhenSomethingChanged() {
        val first = CallerInfo(null, total = 4)
        assertEquals(Alert.SOUND, AfterCallRules.decide(call(1, "7760934418", CallType.MISSED), first, noon).alert)
        // Told at 4. The same number calls again and 4 still have it: nothing.
        val again = CallerInfo(null, total = 4, lastNotified = 4)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(2, "7760934418", CallType.MISSED), again, noon).alert)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(3, "7760934418", CallType.OUTGOING), again, noon).alert)
        // Now 6 have it: told again.
        val grew = CallerInfo(null, total = 6, lastNotified = 4)
        assertEquals(Alert.SOUND, AfterCallRules.decide(call(4, "7760934418", CallType.MISSED), grew, noon).alert)
        // A saved contact told at 12 and no new people: nothing, even though the saved baseline is older.
        val savedAgain = CallerInfo("Kiran Gowda", total = 12, previousTotal = 9, lastNotified = 12)
        assertEquals(Alert.NONE, AfterCallRules.decide(call(5, "9900148823", CallType.ANSWERED), savedAgain, noon).alert)
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
    fun handledAndOldCallsAreSkipped() {
        val calls = listOf(call(1, "7760934418", CallType.MISSED), call(2, "9900148823", CallType.OUTGOING),
            call(3, "9900148823", CallType.ANSWERED, 60L * 24 * 8))
        val plan = CallCatchUp.plan(calls, handled = setOf(1L), now = now)
        assertEquals(listOf(2L), plan.toNotify.map { it.id })       // dialled calls are told too
        assertEquals(emptyList<Long>(), plan.tooLate.map { it.id })       // older than a week: ignored
    }

    @Test
    fun healthCountsCallsCheckedInTime() {
        val day = HealthDay("2026-11-16").seen(true).seen(false).seen(true)
        assertEquals(HealthDay("2026-11-16", callsSeen = 3, callsChecked = 2), day)
        assertTrue(HealthDay.needsFix(2) && !HealthDay.needsFix(1))
    }

    @Test
    fun healthCountsHowFastCallsWereChecked() {
        val day = HealthDay("2026-11-23")
            .seen(true, secondsAfterEnd = 2)       // within 10 s (and so within a minute)
            .seen(true, secondsAfterEnd = 45)      // within a minute
            .seen(true, secondsAfterEnd = 600)     // late, but in the hour
            .seen(false, secondsAfterEnd = 3)      // too late to tell: never counted as fast
            .seen(true)                            // speed not known
        assertEquals(HealthDay("2026-11-23", callsSeen = 5, callsChecked = 4, within10s = 1, within60s = 2), day)
    }

    @Test
    fun aCallEndsWhenItIsLoggedOrElseAfterItsLength() {
        assertEquals(5_000L, CallEvent(1, "9845012345", CallType.MISSED, at = 1_000, loggedAt = 5_000).endedAt)
        assertEquals(61_000L, CallEvent(1, "9845012345", CallType.ANSWERED, at = 1_000, duration = 60).endedAt)
    }

    @Test
    fun hiddenForeignAndIndianCallsAreToldApart() {
        fun call(raw: String?) = CallEvent(1, raw, CallType.MISSED, 0)
        assertTrue(call(null).isHidden)
        assertTrue(call("-1").isHidden)
        assertTrue(call("-2").isHidden)
        assertFalse(call("+971 50 123 4567").isHidden)
        assertTrue(call("+971 50 123 4567").isForeign)
        assertEquals("+971501234567", call("+971 50 123 4567").e164)   // checked like any number
        assertFalse(call("98450 31207").isForeign)
        assertEquals("+919845031207", call("098450 31207").e164)
        assertFalse(call("121").isHidden)
        assertFalse(call("121").isForeign)
        assertEquals("+91121", call("121").e164)
    }
}
