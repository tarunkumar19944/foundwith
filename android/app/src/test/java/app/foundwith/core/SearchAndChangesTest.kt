package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndChangesTest {
    @Test
    fun searchKnowsNumbersFromNames() {
        assertEquals(SearchQuery.Empty, SearchInput.classify("  "))
        assertEquals(SearchQuery.Name("Ravi"), SearchInput.classify("Ravi"))
        assertEquals(SearchQuery.Mobile("+919845172046"), SearchInput.classify("98451 72046"))
        assertEquals(SearchQuery.Mobile("+919845172046"), SearchInput.classify("+91-98451-72046"))
        assertEquals(SearchQuery.Mobile("+919845172046"), SearchInput.classify("०९८४५१७२०४६"))
        assertEquals(SearchQuery.Incomplete, SearchInput.classify("98"))
        // Every kind of number (23 Nov 2026): a landline is searched at once; short and foreign
        // numbers wait for the Search button.
        assertEquals(SearchQuery.Mobile("+914426634410"), SearchInput.classify("044 2663 4410"))
        assertEquals(SearchQuery.Mobile("+9198451", auto = false), SearchInput.classify("98451"))
        assertEquals(SearchQuery.Mobile("+971501234567", auto = false), SearchInput.classify("+971 50 123 4567"))
        assertEquals(SearchQuery.Mobile("+91121", auto = false), SearchInput.classify("121"))
        assertTrue(SearchInput.matches("Ravi Kumar (Work)", "ra ku"))
        assertTrue(SearchInput.matches("Dr. Anand", "anand"))
        assertFalse(SearchInput.matches("Ravi Kumar", "kumarr"))
    }

    @Test
    fun changesAreCountedFromWhatThePersonLastSaw() {
        val day = 24 * 60 * 60 * 1000L
        var log = Changes.update(ChangeLog(), mapOf("kiran" to 9, "suresh" to 12), now = 0, first = true)
        assertEquals(emptyMap<String, CountChange>(), log.changes)
        log = Changes.update(log, mapOf("kiran" to 12, "suresh" to 12, "new" to 3), now = 1000)
        assertEquals(CountChange("kiran", 9, 12, 1000), log.changes["kiran"])
        assertNull(log.changes["new"])                                  // a new card isn't "more people"
        log = Changes.update(log, mapOf("kiran" to 13, "suresh" to 12, "new" to 3), now = 2000)
        assertEquals(CountChange("kiran", 9, 13, 2000), log.changes["kiran"])   // still counted from 9
        log = Changes.update(log, mapOf("kiran" to 11, "suresh" to 12, "new" to 3), now = 3000)
        assertEquals(CountChange("kiran", 9, 11, 2000), log.changes["kiran"])   // shrank, same time
        log = Changes.update(log, mapOf("kiran" to 9, "suresh" to 12, "new" to 3), now = 4000)
        assertNull(log.changes["kiran"])                                   // nothing left of it
        log = Changes.update(log, mapOf("suresh" to 16, "new" to 3), now = 5000)
        assertEquals(4, log.changes["suresh"]?.delta)
        assertEquals(setOf("suresh"), Changes.update(log, mapOf("suresh" to 16), now = 5000 + 6 * day).changes.keys)
        assertEquals(emptySet<String>(), Changes.update(log, mapOf("suresh" to 16), now = 5000 + 8 * day).changes.keys)
    }

    @Test
    fun updatesAreNewestFirstWithDotsForWhatsNew() {
        val rows = UpdatesFeed.rows(callKeys = listOf("call" to 500L, "old call" to 50L), changeKeys = listOf("kiran" to 300L, "call" to 100L),
            lastOpenedAt = 200, startOfToday = 250)
        assertEquals(listOf("call", "kiran", "old call"), rows.map { it.key })
        assertEquals(listOf(true, true, false), rows.map { it.isNew })
        assertEquals(listOf(true, true, false), rows.map { it.isToday })
    }

    @Test
    fun contactEntriesCarryTheirName() {
        val entry = ContactEntry.encode("98451 72046", " Mahesh Electricals ")
        assertEquals("98451 72046", ContactEntry.raw(entry))
        assertEquals("Mahesh Electricals", ContactEntry.name(entry))
        assertEquals("98451 72046", ContactEntry.encode("98451 72046", "98451 72046"))     // a number isn't a name
        assertNull(ContactEntry.name("98451 72046"))
        assertEquals("+919845172046", ContactEntry.normaliser(entry))
        // Renaming a contact is an add (with the new name) and no removal: the number is still there.
        val before = Snapshot(contacts = setOf(ContactEntry.encode("98451 72046", "Mahesh")))
        val after = Snapshot(contacts = setOf(ContactEntry.encode("98451 72046", "Mahesh Electricals")))
        val diff = SnapshotDiff.diff(before, after, ContactEntry.normaliser)
        assertEquals(listOf(ContactEntry.encode("98451 72046", "Mahesh Electricals")), diff.contactsAdded)
        assertEquals(emptyList<String>(), diff.contactsRemoved)
    }

    @Test
    fun fullSyncWeekly() {
        val week = 7 * 24 * 60 * 60 * 1000L
        assertTrue(SyncPolicy.fullDue(0, 100))
        assertFalse(SyncPolicy.fullDue(1000, 1000 + week - 1))
        assertTrue(SyncPolicy.fullDue(1000, 1000 + week))
    }

    @Test
    fun joinsAndNewlyKnownPeopleAreNoticedAfterTheFirstLook() {
        val t = mapOf("mine" to 5, "priya" to 3, "caller" to 2)
        var log = Changes.update(ChangeLog(), t, now = 0, first = true, members = setOf("meena"), known = mapOf("mine" to setOf("m1")))
        assertEquals(emptyMap<String, Long>(), log.joined)
        log = Changes.update(log, t, now = 100, members = setOf("meena", "priya"), known = mapOf("mine" to setOf("m1", "p1"), "caller" to setOf("k1")))
        assertEquals(setOf("priya"), log.joined.keys)
        assertEquals(listOf("p1"), log.gains["mine"]?.ids)
        assertEquals(null, log.gains["caller"])                     // first time tracked: only the baseline
        log = Changes.update(log, t, now = 200, members = setOf("meena", "priya"), known = mapOf("mine" to setOf("m1", "p1", "s1"), "caller" to setOf("k1", "k2")))
        assertEquals(listOf("p1", "s1"), log.gains["mine"]?.ids)
        assertEquals(listOf("k2"), log.gains["caller"]?.ids)
        // Old logs without members or known ids start tracking without reporting everyone as new.
        val old = Changes.update(ChangeLog(baseline = t), t, now = 300, members = setOf("meena", "priya"), known = mapOf("mine" to setOf("m1")))
        assertEquals(emptyMap<String, Long>(), old.joined)
        assertEquals(emptyMap<String, KnownGain>(), old.gains)
    }
}
