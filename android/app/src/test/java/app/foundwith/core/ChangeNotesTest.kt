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
}
