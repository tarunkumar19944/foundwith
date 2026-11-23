package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangeNotesTest {
    private val old = ChangeLog(changes = mapOf("a" to CountChange("a", 3, 5, 1L)), gains = emptyMap())
    private fun build(items: List<ChangeItem>) = ChangeNotes.build(items, label = { "L$it" }, saved = { it == "a" }, holders = { it })

    @Test
    fun oneChangeSaysWhatItIsAndSeveralAreMerged() {
        val updated = ChangeLog(changes = mapOf("a" to CountChange("a", 3, 8, 2L), "me" to CountChange("me", 20, 22, 2L)))
        val items = ChangeNotes.items(old, updated, mineRef = "me")
        assertEquals(2, items.size)
        val note = build(items)!!
        assertEquals("2 changes on WhoHas", note.title)
        assertNull(build(emptyList()))
        assertEquals(1, build(listOf(ChangeItem.MineUp(2)))!!.count)
    }

    @Test
    fun nothingNewMeansNothing() {
        assertEquals(emptyList<ChangeItem>(), ChangeNotes.items(old, old, null))
        assertEquals(emptyList<ChangeItem>(), ChangeNotes.items(null, old, null))
    }

    @Test
    fun eachChangeIsItsOwnNotification() {
        val items = listOf(ChangeItem.MineUp(1, 23), ChangeItem.CountUp("a", 2, 14), ChangeItem.NowKnown("b", listOf("Kiran Sir")),
            ChangeItem.NowKnown("a", listOf("Meena")))
        val notes = ChangeNotes.each(items, label = { "L$it" }, holders = { it })
        assertEquals(3, notes.size)                                     // "a" twice: one notification
        assertEquals(ChangeNotes.One(null, "1 more person has your number", "Now 23"), notes[0])
        assertEquals("La", notes[1].title)
        assertEquals("Now known to Meena · 2 more people have this number · now 14", notes[1].text)
        assertEquals(ChangeNotes.One("b", "Lb", "Now known to Kiran Sir"), notes[2])
    }
}
