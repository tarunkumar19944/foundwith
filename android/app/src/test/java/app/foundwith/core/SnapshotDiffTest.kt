package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDiffTest {
    private val own = "+919845031207"

    @Test
    fun diffListsWhatWasAddedAndRemovedPerSource() {
        val before = Snapshot(contacts = setOf("98451 72046", "99860 31412"), callLog = setOf("7619155380"))
        val now = Snapshot(contacts = setOf("99860 31412", "70221 34567"), callLog = setOf("7619155380", "121"))
        val changes = SnapshotDiff.diff(before, now)
        assertEquals(listOf("70221 34567"), changes.contactsAdded)
        assertEquals(listOf("98451 72046"), changes.contactsRemoved)
        assertEquals(listOf("121"), changes.callLogAdded)
        assertTrue(changes.callLogRemoved.isEmpty())
        assertFalse(changes.isEmpty)
        assertTrue(SnapshotDiff.diff(now, now).isEmpty)
    }

    @Test
    fun deletingOneOfTwoCopiesOfANumberRemovesNothing() {
        // Suresh is saved twice, in two formats. Deleting one copy must keep him on the server.
        val before = Snapshot(contacts = setOf("98451 72046", "+91 98451 72046", "121"))
        val oneLeft = Snapshot(contacts = setOf("+91 98451 72046"))
        val changes = SnapshotDiff.diff(before, oneLeft)
        assertTrue(changes.isEmpty)                                   // "121" was never stored either
        assertEquals(oneLeft, SnapshotDiff.afterChanges(before, oneLeft, changes, emptySet(), own))

        // Deleting the last copy does send it.
        assertEquals(listOf("+91 98451 72046"), SnapshotDiff.diff(oneLeft, Snapshot()).contactsRemoved)

        // Re-typing a number in another format is an addition, never a removal.
        val retyped = SnapshotDiff.diff(Snapshot(contacts = setOf("98451 72046")), Snapshot(contacts = setOf("098451-72046")))
        assertEquals(listOf("098451-72046"), retyped.contactsAdded)
        assertTrue(retyped.contactsRemoved.isEmpty())
    }

    @Test
    fun bigChangesFallBackToAFullSync() {
        val many = (1..501).map { "98450%05d".format(it) }.toSet()
        assertTrue(SnapshotDiff.needsFullSync(SnapshotDiff.diff(Snapshot(), Snapshot(contacts = many))))
        assertFalse(SnapshotDiff.needsFullSync(SnapshotDiff.diff(Snapshot(), Snapshot(contacts = many.drop(1).toSet()))))
    }

    @Test
    fun deferredAndOptedOutNumbersAreOfferedAgain() {
        // The server stored the plumber, deferred the clinic (allowance used up), and skipped "121".
        val sent = listOf("98451 72046", "99860 31412", "121", "+91 98450 31207")
        val accepted = SnapshotDiff.accepted(sent, storedE164 = setOf("+919845172046"), ownE164 = own)
        assertEquals(setOf("98451 72046", "121", "+91 98450 31207"), accepted)
    }

    @Test
    fun snapshotAfterChangesAndAfterFullSync() {
        val before = Snapshot(contacts = setOf("98451 72046"), callLog = emptySet())
        val changes = ChangeSet(
            contactsAdded = listOf("99860 31412", "70221 34567"), contactsRemoved = listOf("98451 72046"),
            callLogAdded = listOf("7619155380"), callLogRemoved = emptyList(),
        )
        val now = Snapshot(contacts = setOf("99860 31412", "70221 34567"), callLog = setOf("7619155380"))
        val after = SnapshotDiff.afterChanges(before, now, changes, setOf("+919986031412", "+917619155380"), own)
        assertEquals(setOf("99860 31412"), after.contacts)          // 70221 34567 was deferred
        assertEquals(setOf("7619155380"), after.callLog)

        val phone = Snapshot(contacts = setOf("98451 72046", "99860 31412"), callLog = setOf("7619155380"))
        val full = SnapshotDiff.afterFullSync(phone, setOf("+919845172046", "+917619155380"), own)
        assertEquals(setOf("98451 72046"), full.contacts)
        assertEquals(setOf("7619155380"), full.callLog)
    }
}
