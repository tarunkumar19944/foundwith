package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words for people who joined with your link, and how far the next level is (23 Nov 2026). */
class LinkJoinsTest {
    @Test
    fun namesWhoJoined() {
        assertEquals("Ravi Kumar joined with your link", LinkJoins.title(listOf("Ravi Kumar")))
        assertEquals("Someone joined with your link", LinkJoins.title(listOf(null)))
        assertEquals("Ravi and Asha joined with your link", LinkJoins.title(listOf("Ravi Kumar", "Asha Rao")))
        assertEquals("Ravi and 1 other joined with your link", LinkJoins.title(listOf("Ravi Kumar", null)))
        assertEquals("Ravi and 3 others joined with your link", LinkJoins.title(listOf("Ravi Kumar", "Asha", null, null)))
        assertEquals("3 people joined with your link", LinkJoins.title(listOf(null, null, null)))
        assertEquals("Ravi and Asha already joined with your link.", LinkJoins.already(listOf("Ravi", "Asha")))
    }

    @Test
    fun onlyClaimsWhatIsKnownAboutYourNumber() {
        assertEquals("Has your number: +1", LinkJoins.rowLine(1, 1))
        assertEquals("Both have your number: +2", LinkJoins.rowLine(2, 2))
        assertEquals("All 3 have your number: +3", LinkJoins.rowLine(3, 3))
        assertEquals("1 of them has your number: +1", LinkJoins.rowLine(3, 1))
        assertEquals("2 of them have your number: +2", LinkJoins.rowLine(3, 2))
        assertEquals("Each one who has your number adds 1.", LinkJoins.rowLine(2, 0))
    }

    @Test
    fun theNotificationSaysTheCountAndTheNextLevel() {
        assertEquals("Has your number. 9 people have it now: 1 more and you’re Popular.", LinkJoins.noteText(true, 9))
        assertEquals("Has your number. 52 people have it now.", LinkJoins.noteText(true, 52))
        assertEquals("8 people have your number. 2 more and you’re Popular.", LinkJoins.noteText(false, 8))
        assertEquals("No one on WhoHas has your number yet. 10 more and you’re Popular.", LinkJoins.noteText(false, 0))
        assertEquals("1 more and you’re Popular", LinkJoins.levelLine(9))
        assertNull(LinkJoins.levelLine(50))
    }

    @Test
    fun howManyCouldAddToYourCount() {
        assertEquals("38 people you call aren’t on WhoHas", LinkJoins.reach(38, called = true))
        assertEquals("1 person you call isn’t on WhoHas", LinkJoins.reach(1, called = true))
        assertEquals("120 of your contacts aren’t on WhoHas", LinkJoins.reach(120, called = false))
        assertEquals("They likely have your number. Each one who joins can add 1 to your 8.", LinkJoins.reachInvite(8))
        assertEquals(LinkJoins.REACH_LINE, LinkJoins.reachInvite(0))
    }

    @Test
    fun theNextLevelStep() {
        assertEquals(Levels.Step(2, Level.POPULAR, 0.8f), Levels.step(8))
        assertEquals(Levels.Step(10, Level.POPULAR, 0f), Levels.step(0))
        assertEquals(27, Levels.step(23)!!.left)
        assertEquals(Level.HIGHLY_POPULAR, Levels.step(23)!!.next)
        assertEquals(0.325f, Levels.step(23)!!.done, 0.001f)
        assertNull(Levels.step(52))
        assertEquals("2 more → Popular", Levels.barLine(8))
        assertEquals("27 more → Highly popular", Levels.barLine(23))
        assertNull(Levels.barLine(50))
        assertEquals("At 10 people, the Popular tag shows beside your number for everyone who has it.", Levels.tagNote(8))
        assertEquals("At 50 people, the Highly popular tag shows beside your number for everyone who has it.", Levels.tagNote(23))
        assertNull(Levels.tagNote(60))
        assertEquals("+2 this week", HolderText.thisWeek(2))
    }

    @Test
    fun aJoinWithYourLinkIsNoticedOnce() {
        // First refresh: whoever already joined is only the baseline.
        var log = Changes.update(ChangeLog(), mapOf("me" to 8), now = 0, first = true, linkJoined = setOf("a"))
        assertEquals(emptyMap<String, Long>(), log.linked)
        // An update from before this was tracked: still only the baseline.
        log = Changes.update(log.copy(linkRefs = null), mapOf("me" to 8), now = 100, linkJoined = setOf("a"))
        assertEquals(emptyMap<String, Long>(), log.linked)
        // Ravi joins: noticed now.
        log = Changes.update(log, mapOf("me" to 8), now = 200, linkJoined = setOf("a", "ravi"))
        assertEquals(mapOf("ravi" to 200L), log.linked)
        // The list didn't come (offline): nothing is lost or told again.
        log = Changes.update(log, mapOf("me" to 9), now = 300, linkJoined = null)
        assertEquals(mapOf("ravi" to 200L), log.linked)
        assertEquals(setOf("a", "ravi"), log.linkRefs)
        // A week later it is no longer recent.
        log = Changes.update(log, mapOf("me" to 9), now = 200 + Changes.KEEP_MS + 1, linkJoined = setOf("a", "ravi"))
        assertEquals(emptyMap<String, Long>(), log.linked)
    }
}
