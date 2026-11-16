package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HolderTextTest {
    private fun people(vararg names: String) = names.map { ShownPerson(it, known = false) }

    @Test
    fun foundWithLine() {
        assertEquals("Found with Ravi Kumar", HolderText.foundWith(people("Ravi Kumar"), total = 1))
        assertEquals("Found with Ravi Kumar and Priya Sharma", HolderText.foundWith(people("Ravi Kumar", "Priya Sharma"), 2))
        assertEquals("Found with Ravi, Priya and Meena", HolderText.foundWith(people("Ravi", "Priya", "Meena"), 3))
        assertEquals("Found with Ravi, Priya, Meena and 1 other", HolderText.foundWith(people("Ravi", "Priya", "Meena", "Sana"), 4))
        assertEquals("Found with Ravi, Priya, Meena and 1,19,997 others",
            HolderText.foundWith(people("Ravi", "Priya", "Meena"), 120_000))
        assertEquals("Found with Ravi, Priya, Meena and 9,997+ others",
            HolderText.foundWith(people("Ravi", "Priya", "Meena"), 10_000, lowerBound = true))
        assertEquals("Found with 12 people", HolderText.foundWith(emptyList(), 12))
        assertEquals("Found with 1 person", HolderText.foundWith(emptyList(), 1))
        assertEquals("Found with no one yet", HolderText.foundWith(people("Ravi"), 0))
        assertEquals("Only you have this number", HolderText.foundWith(emptyList(), 0, onYourPhone = true))
    }

    @Test
    fun labelsComeFromTheSameCount() {
        assertEquals(null, HolderText.label(9))
        assertEquals("Popular number", HolderText.label(10))
        assertEquals("Popular number", HolderText.label(49))
        assertEquals("Highly popular number", HolderText.label(50))
        assertEquals("6 more people to a Popular number", HolderText.nextLabel(4))
        assertEquals("1 more person to a Popular number", HolderText.nextLabel(9))
        assertEquals("19 more people to Highly popular", HolderText.nextLabel(31))
        assertEquals(null, HolderText.nextLabel(64))
        assertEquals("13 people have it. 10 to 49 is a popular number.", HolderText.labelMeaning(13))
        assertEquals(null, HolderText.labelMeaning(4))
    }

    @Test
    fun changesReadPlainly() {
        assertEquals("Now found with 12 · 3 more", HolderText.change(12, 3))
        assertEquals("Today: 3 more people · now a Popular number", HolderText.today(3, before = 9, now = 12))
        assertEquals("Today: 1 more person", HolderText.today(1, before = 3, now = 4))
        assertEquals("Today: 2 more people", HolderText.today(2, before = 12, now = 14))
        assertEquals("Today: 2 more people · now a Highly popular number", HolderText.today(2, before = 49, now = 51))
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
