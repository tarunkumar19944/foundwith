package app.foundwith.data

import app.foundwith.core.ContactEntry
import app.foundwith.core.PhoneNumbers
import app.foundwith.core.Snapshot
import app.foundwith.core.SnapshotDiff
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a sync did, for the UI and the logs. */
data class SyncOutcome(val kind: Kind, val stats: SyncStats = SyncStats()) {
    enum class Kind { FULL, CHANGES, NOTHING_CHANGED }
}

/**
 * Keeps the server's copy of the person's numbers in step with the phone.
 *
 * A full sync sends the whole address book (each contact with the name it is saved under) and
 * replaces what the server has. After that, a change on the phone (a contact saved or renamed, a
 * call) is sent as a small list of changes, worked out against the snapshot of what the server
 * last accepted. The whole address book goes again once a week, or when the changes are too big
 * or the server asks for it.
 */
class SyncEngine(
    private val api: ApiClient,
    private val book: DeviceBook,
    private val store: LocalStore,
    private val auth: AuthManager,
) {
    private val mutex = Mutex()
    private val normalise = ContactEntry.normaliser

    suspend fun fullSync(): SyncOutcome = mutex.withLock { fullSyncLocked() }

    suspend fun syncChanges(): SyncOutcome = mutex.withLock {
        val previous = store.snapshot() ?: return@withLock fullSyncLocked()
        val phone = readPhone()
        val changes = SnapshotDiff.diff(previous, phone.snapshot, normalise)
        if (changes.isEmpty) {
            mergeIndex(emptyMap(), phone.names, replace = false)
            val tidy = SnapshotDiff.afterChanges(previous, phone.snapshot, changes, emptySet(), null, normalise)
            if (tidy != previous) store.saveSnapshot(tidy)
            return@withLock SyncOutcome(SyncOutcome.Kind.NOTHING_CHANGED)
        }
        if (SnapshotDiff.needsFullSync(changes)) return@withLock fullSyncLocked()

        val result = try {
            api.sendChanges(changes)
        } catch (e: ApiException) {
            if (e.code == "full_sync_required") return@withLock fullSyncLocked()
            throw e
        }
        mergeIndex(result.refs, phone.names, replace = false)
        store.saveSnapshot(SnapshotDiff.afterChanges(previous, phone.snapshot, changes, result.refs.keys, ownE164(), normalise))
        SyncOutcome(SyncOutcome.Kind.CHANGES, result.stats)
    }

    private suspend fun fullSyncLocked(): SyncOutcome {
        val phone = readPhone()
        val result = api.syncNumbers(phone.snapshot.contacts, phone.snapshot.callLog)
        mergeIndex(result.refs, phone.names, replace = true)
        store.saveSnapshot(SnapshotDiff.afterFullSync(phone.snapshot, result.refs.keys, ownE164(), normalise))
        store.lastFullSyncAt = System.currentTimeMillis()
        return SyncOutcome(SyncOutcome.Kind.FULL, result.stats)
    }

    private class PhoneState(val snapshot: Snapshot, val names: Map<String, String>)

    private suspend fun readPhone(): PhoneState {
        val contacts = book.contacts()
        val names = HashMap<String, String>()
        for (entry in contacts) {
            val e164 = entry.e164 ?: continue
            val name = entry.shownName?.trim()
            if (!name.isNullOrEmpty() && e164 !in names) names[e164] = name
        }
        return PhoneState(
            snapshot = Snapshot(
                contacts = contacts.mapTo(HashSet()) { ContactEntry.encode(it.raw, it.name) },
                callLog = book.callLogNumbers(),
            ),
            names = names,
        )
    }

    private fun ownE164(): String? = PhoneNumbers.normalise(auth.signedInPhone())

    /**
     * Record which reference stands for which of the person's numbers, and what they call it now.
     * Every label takes the contact's current name, so a renamed contact shows its new name; a
     * number no longer in contacts loses its name.
     */
    private suspend fun mergeIndex(refs: Map<String, String>, names: Map<String, String>, replace: Boolean) {
        val old = if (replace) NumberIndex() else store.index()
        val byRef = HashMap<String, NumberLabel>()
        for ((ref, label) in old.byRef) byRef[ref] = label.copy(name = names[label.e164])
        val refByE164 = HashMap(old.refByE164)
        for ((e164, ref) in refs) {
            byRef[ref] = NumberLabel(e164, names[e164])
            refByE164[e164] = ref
        }
        val updated = NumberIndex(byRef, refByE164)
        if (updated != old) store.saveIndex(updated)
    }
}
