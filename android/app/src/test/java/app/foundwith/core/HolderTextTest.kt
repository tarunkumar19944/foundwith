package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HolderTextTest {
    private fun people(vararg names: String) = names.map { ShownPerson(it, known = false) }

    @Test
    fun knownToNamesPeopleYouKnowThenCountsTheRest() {
        assertEquals("Known to Priya", HolderText.knownTo(listOf("Priya"), total = 1))
        assertEquals("Known to Priya and Meena", HolderText.knownTo(listOf("Priya", "Meena"), 2))
        assertEquals("Known to Priya, Meena and 10 more", HolderText.knownTo(listOf("Priya", "Meena", "Anil"), 12))
        assertEquals("Known to Priya and 2 more", HolderText.knownTo(listOf("Priya"), 3))
        assertEquals("Known to Priya, Meena and 1,19,998 more", HolderText.knownTo(listOf("Priya", "Meena"), 120_000))
        assertEquals("Known to Priya, Meena and 9,998+ more", HolderText.knownTo(listOf("Priya", "Meena"), 10_000, lowerBound = true))
        assertEquals("4 people have this number", HolderText.knownTo(emptyList(), 4))
        assertEquals("1 person has this number", HolderText.knownTo(emptyList(), 1))
        assertEquals("No one on WhoHas has this number", HolderText.knownTo(emptyList(), 0))
        assertEquals("No one else on WhoHas has it yet", HolderText.knownTo(emptyList(), 0, onYourPhone = true))
    }

    @Test
    fun contactRowsPutTheCountFirst() {
        assertEquals("5 people have it · You know Priya and Meena", HolderText.haveItLine(listOf("Priya", "Meena"), 2, 5))
        assertEquals("14 people have it · You know Meena, Deepa and 4 more", HolderText.haveItLine(listOf("Meena", "Deepa"), 6, 14))
        assertEquals("1 person has it", HolderText.haveItLine(emptyList(), 0, 1))
        assertEquals("No one else on WhoHas has it yet", HolderText.haveItLine(emptyList(), 0, 0, onYourPhone = true))
        assertEquals("You know Priya, Meena and 4 more", HolderText.youKnowNames(listOf("Priya", "Meena", "Kiran"), 6))
        assertEquals(null, HolderText.youKnowNames(emptyList(), 0))
        // Someone only in the call log with no name: counted, never shown as a bare number.
        assertEquals("You know 1 of them", HolderText.youKnowNames(emptyList(), 1))
        assertEquals("1 person has it · You know 1 of them", HolderText.haveItLine(emptyList(), 1, 1))
        assertEquals("You know 3 of them", HolderText.youKnowCount(3))
        assertEquals("You don't know any of them", HolderText.youKnowCount(0))
        assertEquals("23 people have your number", HolderText.haveYours(23))
        assertEquals("1 person has your number", HolderText.haveYours(1))
    }

    @Test
    fun labelsComeFromTheSameCount() {
        assertEquals(null, HolderText.label(9))
        assertEquals("Many people have this number", HolderText.label(10))
        assertEquals("Many people have this number", HolderText.label(49))
        assertEquals("Lots of people have this number", HolderText.label(50))
        assertEquals("Lots of people have this number now", HolderText.crossed(49, 51))
        assertEquals("Many people have this number now", HolderText.crossed(9, 12))
        assertEquals(null, HolderText.crossed(12, 14))
        assertEquals(null, HolderText.crossed(3, 4))
    }

    @Test
    fun changesReadPlainly() {
        assertEquals("3 more people have this number · now 12", HolderText.change(12, 3))
        assertEquals("1 more person has this number · now 4", HolderText.change(4, 1))
        assertEquals("3 more people have Ravi's number · now 12", HolderText.changeFor("Ravi", 12, 3))
        assertEquals("2 more people have your number", HolderText.mineUp(2))
        assertEquals("1 more person has your number", HolderText.mineUp(1))
        assertEquals("+3 today", HolderText.today(3))
        assertEquals("+5 this week", HolderText.thisWeek(5))
    }

    @Test
    fun theMeaningSheetMatchesTheCard() {
        val lines = HolderText.meaning(total = 12, knownCount = 3)
        assertEquals("12 people have this number.", lines[0].first)
        assertEquals("You know 3 of them.", lines[1].first)
        assertEquals("9 you don't know.", lines[2].first)
        assertEquals("Many people have it.", lines[3].first)
        assertEquals("It's often a business, or someone who calls a lot of people.", lines[3].second)
        assertEquals(1, HolderText.meaning(0, 0).size)
        assertEquals("23 people have your number.", HolderText.meaning(23, 6, mine = true)[0].first)
    }

    @Test
    fun indianGrouping() {
        assertEquals("999", HolderText.grouped(999))
        assertEquals("1,000", HolderText.grouped(1_000))
        assertEquals("12,345", HolderText.grouped(12_345))
        assertEquals("1,22,000", HolderText.grouped(122_000))
        assertEquals("10,00,000", HolderText.grouped(1_000_000))
        assertEquals("1,23,45,678", HolderText.grouped(12_345_678))
    }

    @Test
    fun initials() {
        assertEquals("RK", HolderText.initials("Ravi Kumar"))
        assertEquals("P", HolderText.initials("Priya"))
        assertEquals("AR", HolderText.initials("Arjun (Office) Rao"))
        assertEquals("#", HolderText.initials("..."))
        assertEquals("रक", HolderText.initials("रवि कुमार"))
        assertEquals("ರಕ", HolderText.initials("ರವಿ ಕುಮಾರ್"))
        assertEquals("DA", HolderText.initials("Dr. Anand"))
    }
}
