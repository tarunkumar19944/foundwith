package app.foundwith.core

/**
 * What the server has accepted, as the raw strings the phone holds. The app keeps one of these
 * after every sync, so the next change on the phone can be sent as a small list of changes
 * instead of the whole address book.
 */
data class Snapshot(val contacts: Set<String> = emptySet(), val callLog: Set<String> = emptySet())

data class ChangeSet(
    val contactsAdded: List<String>,
    val contactsRemoved: List<String>,
    val callLogAdded: List<String>,
    val callLogRemoved: List<String>,
) {
    val isEmpty: Boolean
        get() = contactsAdded.isEmpty() && contactsRemoved.isEmpty() &&
            callLogAdded.isEmpty() && callLogRemoved.isEmpty()

    val largestList: Int
        get() = maxOf(contactsAdded.size, contactsRemoved.size, callLogAdded.size, callLogRemoved.size)
}

object SnapshotDiff {
    /** The server accepts up to this many entries per list in one changes call. */
    const val MAX_PER_LIST = 500

    /**
     * What changed since the last snapshot the server accepted. Numbers go to the server exactly as
     * saved, but a removal is only sent when nothing left in that source is the same number: the
     * same number is often saved twice ("98451 72046" as "Suresh" and "+91 98451 72046" as
     * "Plumber"), and deleting one of them must not take the number off the server.
     */
    fun diff(
        previous: Snapshot,
        current: Snapshot,
        normalise: (String) -> String? = { PhoneNumbers.normalise(it) },
    ): ChangeSet = ChangeSet(
        contactsAdded = (current.contacts - previous.contacts).sorted(),
        contactsRemoved = gone(previous.contacts - current.contacts, current.contacts, normalise),
        callLogAdded = (current.callLog - previous.callLog).sorted(),
        callLogRemoved = gone(previous.callLog - current.callLog, current.callLog, normalise),
    )

    private fun gone(removed: Set<String>, remaining: Set<String>, normalise: (String) -> String?): List<String> {
        if (removed.isEmpty()) return emptyList()
        val stillHeld = remaining.mapNotNullTo(HashSet()) { normalise(it) }
        // Entries that were never a number ("121", text) were never stored, so there is nothing to remove.
        return removed.filter { raw -> normalise(raw)?.let { it !in stillHeld } ?: false }.sorted()
    }

    /** Too much changed at once (a restored phone, a big import): send the full address book. */
    fun needsFullSync(changes: ChangeSet): Boolean = changes.largestList > MAX_PER_LIST

    /**
     * Which of the raw numbers sent can be recorded as done. A number is done when the server
     * stored it (its E.164 form came back with a reference), when it can never match anything
     * (it normalises to nothing), or when it is the person's own number. Anything else was
     * deferred by the new-number allowance or belongs to someone who opted out; it is left out
     * so the next sync offers it again.
     */
    fun accepted(
        sent: Collection<String>,
        storedE164: Set<String>,
        ownE164: String?,
        normalise: (String) -> String? = { PhoneNumbers.normalise(it) },
    ): Set<String> = sent.filterTo(HashSet()) { raw ->
        val e164 = normalise(raw)
        e164 == null || e164 == ownE164 || e164 in storedE164
    }

    /**
     * The snapshot after a changes call that the server answered (or after finding nothing to
     * send): what is still on the phone from before, plus the additions the server took.
     */
    fun afterChanges(
        previous: Snapshot,
        current: Snapshot,
        changes: ChangeSet,
        storedE164: Set<String>,
        ownE164: String?,
        normalise: (String) -> String? = { PhoneNumbers.normalise(it) },
    ): Snapshot = Snapshot(
        contacts = previous.contacts.intersect(current.contacts) +
            accepted(changes.contactsAdded, storedE164, ownE164, normalise),
        callLog = previous.callLog.intersect(current.callLog) +
            accepted(changes.callLogAdded, storedE164, ownE164, normalise),
    )

    /** The snapshot after a full sync that the server answered. */
    fun afterFullSync(
        current: Snapshot,
        storedE164: Set<String>,
        ownE164: String?,
        normalise: (String) -> String? = { PhoneNumbers.normalise(it) },
    ): Snapshot = Snapshot(
        contacts = accepted(current.contacts, storedE164, ownE164, normalise),
        callLog = accepted(current.callLog, storedE164, ownE164, normalise),
    )
}
