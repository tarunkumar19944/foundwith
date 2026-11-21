package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LevelsTest {
    @Test
    fun levelsFollowTheCountsOfTheManyPeopleLabel() {
        assertEquals(Level.NOT_POPULAR, Levels.of(0))
        assertEquals(Level.NOT_POPULAR, Levels.of(9))
        assertEquals(Level.POPULAR, Levels.of(10))
        assertEquals(Level.POPULAR, Levels.of(49))
        assertEquals(Level.HIGHLY_POPULAR, Levels.of(50))
        assertEquals(Level.HIGHLY_POPULAR, Levels.of(10, lowerBound = true))
    }

    @Test
    fun rowsTagOnlyPopularAndHighlyPopular() {
        assertNull(Levels.rowTag(3))
        assertEquals(Level.POPULAR, Levels.rowTag(12))
        assertEquals(Level.HIGHLY_POPULAR, Levels.rowTag(64))
    }

    @Test
    fun progressSaysHowManyMoreReachTheNextLevel() {
        assertEquals("10 more people and you’re Popular", Levels.progress(0))
        assertEquals("2 more people and you’re Popular", Levels.progress(8))
        assertEquals("1 more person and you’re Popular", Levels.progress(9))
        assertEquals("27 more people and you’re Highly popular", Levels.progress(23))
        assertNull(Levels.progress(52))
        assertEquals(27, Levels.left(23))
        assertNull(Levels.left(60))
    }

    @Test
    fun aCardComparesThisNumberWithYours() {
        assertEquals(Comparison(Standing.BEHIND, "Your number is ahead of this one."), Levels.compare(Level.NOT_POPULAR, 23))
        assertEquals(Comparison(Standing.SAME, "Same level as your number."), Levels.compare(Level.POPULAR, 23))
        assertEquals(Comparison(Standing.AHEAD, "More popular than your number. 27 more people and you catch up (Highly popular)."),
            Levels.compare(Level.HIGHLY_POPULAR, 23))
        assertEquals(Comparison(Standing.AHEAD, "More popular than your number. 2 more people and you catch up (Popular)."),
            Levels.compare(Level.HIGHLY_POPULAR, 8))
    }

    @Test
    fun reachingALevelIsToldOnceAndNudgesComeOnceAWeek() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        val reached = LevelRules.decide(announced = 0, total = 10, lastNudgeAt = 0, now = now)
        assertNotNull(reached.note)
        assertEquals("You’re Popular now", reached.note!!.title)
        assertEquals(1, reached.announced)
        assertNull(LevelRules.decide(1, 12, 0, now).note)
        val nudge = LevelRules.decide(1, 48, 0, now)
        assertEquals("2 more people and you’re Highly popular", nudge.note!!.title)
        assertNull(LevelRules.decide(1, 48, now - 2 * day, now).note)
        assertNotNull(LevelRules.decide(1, 48, now - 8 * day, now).note)
        assertNull(LevelRules.decide(2, 70, 0, now).note)
    }

    @Test
    fun copyIsTheTenDigits() {
        assertEquals("7760934418", PhoneNumbers.copyText("+917760934418"))
    }
}
