package app.foundwith.data

import android.util.Log
import app.foundwith.AppGraph
import app.foundwith.BuildConfig
import app.foundwith.core.CallEvent
import app.foundwith.core.ChangeItem
import app.foundwith.core.ChangeNotes
import app.foundwith.core.LevelRules
import app.foundwith.core.Levels
import app.foundwith.core.Naming
import app.foundwith.core.ChangeLog
import app.foundwith.core.Changes
import app.foundwith.core.PhoneNumbers
import app.foundwith.work.Scheduling
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Where setting up stands: what the Home screen shows above the tabs. */
enum class Setup {
    /** Nothing allowed yet: the reward card with Allow. */
    NOT_ALLOWED,
    /** Allowed; the first full sync is running ("Finding who you know…"). */
    FINDING,
    /** The first full sync failed; it keeps trying. */
    FIND_FAILED,
    READY,
}

/** A screen that replaces the whole app until it's dealt with. */
enum class Gate { NONE, UPDATE_NEEDED, SUSPENDED }

/** Everything the screens show, from the phone and from the server. */
data class AppState(
    val profile: Profile? = null,
    val cards: CachedCards? = null,
    val index: NumberIndex = NumberIndex(),
    val contacts: List<SavedNumber> = emptyList(),
    val calls: List<CallEvent> = emptyList(),
    val changes: ChangeLog = ChangeLog(),
    val blocks: List<BlockEntry> = emptyList(),
    val setup: Setup = Setup.NOT_ALLOWED,
    val gate: Gate = Gate.NONE,
    val offline: Boolean = false,
    val refreshing: Boolean = false,
    /** Calls WhoHas only reached after the hour was up, in the last week (the phone stops it). */
    val lateCalls: Int = 0,
    /** Updates newer than this get a dot in Updates: when Updates was looked at before this visit. */
    val dotsSince: Long = 0L,
    /** Updates newer than this count on the Updates tab's badge: when Updates was last looked at. */
    val updatesSeenAt: Long = 0L,
    val loaded: Boolean = false,
) {
    val cardsByRef: Map<String, Card> by lazy(LazyThreadSafetyMode.NONE) {
        cards?.cards.orEmpty().associateBy { it.ref }
    }
    val contactsByE164: Map<String, SavedNumber> by lazy(LazyThreadSafetyMode.NONE) {
        val out = HashMap<String, SavedNumber>()
        for (c in contacts) c.e164?.let { if (it !in out) out[it] = c }
        out
    }
    val callCounts: Map<String, Int> by lazy(LazyThreadSafetyMode.NONE) {
        calls.mapNotNull { it.e164 }.groupingBy { it }.eachCount()
    }
    val blockedRefs: Set<String> by lazy(LazyThreadSafetyMode.NONE) { blocks.mapNotNullTo(HashSet()) { it.ref } }
}

/**
 * The app's state in one place, shared by every screen. Phone data (contacts, calls) is read
 * here; server data (profile, cards, blocks) is refreshed here and cached, so the app opens
 * instantly and works offline with the last answers.
 */
class AppModel(private val graph: AppGraph) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val refreshLock = Mutex()

    private val store get() = graph.store
    private val api get() = graph.api

    fun reset() {
        _state.value = AppState(loaded = true)
    }

    /** Fast: the last saved answers plus the phone's own contacts and calls. No network. */
    suspend fun load() {
        val profile = store.profileJson?.let { runCatching { api.json.decodeFromString<Profile>(it) }.getOrNull() }
        val cards = store.cachedCards()
        val (_, lateAt, _) = store.callState()
        val weekAgo = System.currentTimeMillis() - Changes.KEEP_MS
        _state.update {
            it.copy(
                profile = profile,
                cards = cards,
                index = store.index(),
                contacts = graph.book.contacts(),
                calls = graph.book.calls(),
                changes = store.changeLog() ?: ChangeLog(),
                setup = setupFrom(profile, cards),
                gate = gateFrom(store.gate),
                lateCalls = lateAt.count { t -> t >= weekAgo },
                dotsSince = store.updatesSeenAt,
                updatesSeenAt = store.updatesSeenAt,
                loaded = true,
            )
        }
    }

    /** Re-reads contacts and the call log, for when they changed while the app is open. */
    suspend fun reloadPhone() {
        val contacts = graph.book.contacts()
        val calls = graph.book.calls()
        _state.update { it.copy(contacts = contacts, calls = calls) }
    }

    /** Everything from the server: profile, cards and blocks. Safe to call often. */
    suspend fun refresh() = refreshLock.withLock {
        if (!graph.auth.isSignedIn) return@withLock
        _state.update { it.copy(refreshing = true) }
        try {
            val profile = ensureProfile()
            var cards = fetchCards()
            // The number rules changed (Indian numbers only; extensions, labels and two numbers in a
            // field): send the whole book again once, so the server drops foreign numbers and picks
            // up the ones it couldn't read before.
            if (store.numberRules < PhoneNumbers.RULES) {
                if (store.snapshot() == null) {
                    store.numberRules = PhoneNumbers.RULES
                } else if (graph.book.hasContactsPermission() || graph.book.hasCallLogPermission()) {
                    if (runCatching { graph.sync.fullSync() }.isSuccess) {
                        store.numberRules = PhoneNumbers.RULES
                        cards = fetchCards()
                    }
                }
            }
            // A card can name someone the phone can't place (its map of numbers missed a sync); they'd
            // show as "Someone on your phone". Rebuild the map with a full sync, at most every 6 hours.
            val index = store.index()
            val unplaced = (cards.cards + listOfNotNull(cards.mine)).asSequence().flatMap { it.known }
                .any { holder -> holder.contactRef != null && holder.contactRef !in index.byRef }
            val now = System.currentTimeMillis()
            if (unplaced && now - store.indexRepairedAt > 6 * 3_600_000L &&
                (graph.book.hasContactsPermission() || graph.book.hasCallLogPermission())) {
                store.indexRepairedAt = now
                if (runCatching { graph.sync.fullSync() }.isSuccess) cards = fetchCards()
            }
            val blocks = runCatching { api.blocks().blocks }.getOrElse { _state.value.blocks }
            store.blockedRefs = blocks.mapNotNullTo(HashSet()) { it.ref }
            _state.update {
                it.copy(profile = profile, cards = cards, index = store.index(), changes = store.changeLog() ?: it.changes,
                    blocks = blocks, setup = setupFrom(profile, cards, it.setup), offline = false, gate = Gate.NONE)
            }
            store.gate = null
            registerPush()
            Invites.redeemPending(api, store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OfflineException) {
            _state.update { it.copy(offline = true) }
        } catch (e: ApiException) {
            handle(e)
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
        reloadPhone()
    }

    /**
     * The person tapped Allow and answered Android's questions. With contacts or the call log
     * allowed, they take part: consent is recorded, the whole address book is sent, and the cards
     * come back with names.
     */
    suspend fun startTakingPart() {
        if (!graph.book.hasContactsPermission() && !graph.book.hasCallLogPermission()) return
        _state.update { it.copy(setup = Setup.FINDING) }
        try {
            val profile = ensureProfile()
            if (!profile.sync.enabled || profile.sync.consentVersion != BuildConfig.CONSENT_VERSION) {
                val updated = api.setSync(true, BuildConfig.CONSENT_VERSION)
                saveProfile(updated)
            }
            store.syncEnabledHint = true
            if (store.callsWatchedSince == 0L) store.callsWatchedSince = System.currentTimeMillis()
            graph.sync.fullSync()
            val cards = fetchCards(first = true)
            Scheduling.scheduleAll(graph.context)
            // Start the after-call history now, so the very first call after allowing is news.
            app.foundwith.call.AfterCall.runNow(graph.context)
            _state.update { it.copy(cards = cards, index = store.index(), setup = Setup.READY, offline = false) }
            reloadPhone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            handle(e)
            _state.update { it.copy(setup = Setup.FIND_FAILED) }
            Scheduling.syncNow(graph.context, delaySeconds = 30)
        } catch (e: Exception) {
            Log.w(TAG, "first sync failed", e)
            _state.update { it.copy(setup = Setup.FIND_FAILED, offline = e is OfflineException) }
            Scheduling.syncNow(graph.context, delaySeconds = 30)
        }
    }

    /** "Try now" on "Couldn't finish". */
    suspend fun retryFinding() = startTakingPart()

    /** Best effort: a Tell that doesn't reach the server only loses the "invited you" line. */
    suspend fun recordTell(e164: String) {
        graph.store.markTold(e164)
        runCatching { api.recordTell(e164) }
    }

    suspend fun setName(name: String): String? = try {
        saveProfile(api.rename(name))
        store.nameAsked = true
        null
    } catch (e: ApiException) {
        e.message
    } catch (e: OfflineException) {
        e.message
    }

    suspend fun block(ownerId: String): String? = try {
        api.block(ownerId)
        refreshBlocks()
        null
    } catch (e: ApiException) {
        e.message
    } catch (e: OfflineException) {
        e.message
    }

    suspend fun unblock(ref: String): String? {
        val entry = _state.value.blocks.firstOrNull { it.ref == ref } ?: return null
        return unblockId(entry.id)
    }

    suspend fun unblockId(id: Long): String? = try {
        api.unblock(id)
        refreshBlocks()
        null
    } catch (e: ApiException) {
        e.message
    } catch (e: OfflineException) {
        e.message
    }

    private suspend fun refreshBlocks() {
        val blocks = api.blocks().blocks
        store.blockedRefs = blocks.mapNotNullTo(HashSet()) { it.ref }
        _state.update { it.copy(blocks = blocks) }
        runCatching { fetchCards() }.getOrNull()?.let { cards -> _state.update { it.copy(cards = cards) } }
    }

    /** Remembered when the app goes to the background. */
    fun leftApp() {
        store.lastOpenedAt = System.currentTimeMillis()
    }

    /** Updates opened: the badge clears; what's new keeps its dot for this visit. */
    fun openedUpdates(now: Long = System.currentTimeMillis()) {
        val before = store.updatesSeenAt
        store.updatesSeenAt = now
        _state.update { it.copy(dotsSince = before, updatesSeenAt = now) }
    }

    /** Left Updates (another tab, or the app went away): what came in while it was open was seen too. */
    fun leftUpdates(now: Long = System.currentTimeMillis()) {
        store.updatesSeenAt = now
        _state.update { it.copy(updatesSeenAt = now) }
    }

    fun dismissFix() {
        store.fixDismissedAt = System.currentTimeMillis()
        _state.update { it.copy(lateCalls = 0) }
    }

    private suspend fun ensureProfile(): Profile {
        val profile = try {
            api.profile()
        } catch (e: ApiException) {
            if (e.code != "not_registered") throw e
            api.createProfile()
        }
        saveProfile(profile)
        if (profile.suspended) throw ApiException(403, "account_suspended", "This account is suspended.")
        return profile
    }

    private fun saveProfile(profile: Profile) {
        store.profileJson = api.json.encodeToString(profile)
        _state.update { it.copy(profile = profile) }
    }

    /** Loads up to [MAX_CACHED_CARDS] cards, keeps them, and notes which counts went up. */
    private suspend fun fetchCards(first: Boolean = false): CachedCards {
        val cards = ArrayList<Card>()
        var cursor: String? = null
        var total = 0
        var mine: Card? = null
        var takingPart = true
        do {
            val page = api.cards(cursor, PAGE_SIZE)
            if (cursor == null) {
                mine = page.mine
                takingPart = page.takingPart
            }
            cards += page.cards
            total = page.totalCards
            cursor = page.next
        } while (cursor != null && cards.size < MAX_CACHED_CARDS)
        val circle = try {
            api.circle()
        } catch (e: ApiException) {
            null
        } catch (e: OfflineException) {
            null
        } ?: store.cachedCards()?.circle
        // A card whose counts changed while paging can come back on two pages; show it once.
        val unique = cards.distinctBy { it.ref }
        val cached = CachedCards(System.currentTimeMillis(), total, unique, mine, circle, takingPart)
        store.saveCards(cached)
        if (takingPart) {
            val totals = unique.associate { it.ref to it.total } + listOfNotNull(mine?.let { it.ref to it.total })
            // Known people are tracked for your own number and for numbers only in the call log
            // ("now known to Kiran"); a contact's change is told by its count.
            val known = (unique.filter { !it.inContacts } + listOfNotNull(mine))
                .associate { card -> card.ref to card.known.map { it.id }.toSet() }
            val members = circle?.memberRefs?.toSet()
            val old = store.changeLog()
            val updated = Changes.update(old ?: ChangeLog(), totals, System.currentTimeMillis(),
                first = first || old == null, members = members, known = known)
            store.saveChangeLog(updated)
            // A contact who just joined: tell the person, quietly ("Priya joined WhoHas · Has your number").
            val index = store.index()
            if (graph.store.notifyJoined && !graph.inFront) for (ref in updated.joined.keys - old?.joined?.keys.orEmpty()) {
                val e164 = index.byRef[ref]?.e164 ?: continue
                val name = _state.value.contactsByE164[e164]?.shownName ?: continue
                val hasYours = mine?.known?.any { it.contactRef == ref } == true
                graph.notifier.postJoined(e164, "$name joined WhoHas", if (hasYours) "Has your number" else null)
            }
            if (old != null && !first) notifyChanges(old, updated, mine, unique, index)
            notifyLevel(mine?.total, first)
        }
        return cached
    }

    /**
     * New or changed since the last refresh becomes one silent notification ("2 changes on WhoHas").
     * Counts already told about after a call are left out.
     */
    private fun notifyChanges(old: ChangeLog, updated: ChangeLog, mine: Card?, cards: List<Card>, index: NumberIndex) {
        // The person is looking at Updates: the gold dots show what changed, no notification needed.
        if (!graph.store.notifyChanges || graph.inFront) return
        val contacts = _state.value.contactsByE164
        fun e164(ref: String) = index.byRef[ref]?.e164
        val items = ChangeNotes.items(old, updated, mine?.ref).filter { item ->
            when (item) {
                is ChangeItem.CountUp -> e164(item.ref)?.let { graph.store.lastNotified(it) ?: 0 }?.let { it < item.total } ?: true
                else -> true
            }
        }
        val holderNames = HashMap<String, String>()
        for (card in cards + listOfNotNull(mine)) for (h in card.known) {
            val label = h.contactRef?.let { index.byRef[it] }
            holderNames[h.id] = Naming.person(label?.name, h.name, label?.e164)
        }
        val note = ChangeNotes.build(items,
            label = { ref -> e164(ref)?.let { contacts[it]?.shownName ?: PhoneNumbers.display(it) } ?: "A number" },
            saved = { ref -> e164(ref)?.let { contacts.containsKey(it) } == true },
            holders = { ids -> ids.mapNotNull { holderNames[it] }.filterNot { Naming.looksLikeNumber(it) } }) ?: return
        graph.notifier.postChanges(note.title, note.text)
    }

    /** Your own level: told when you reach a new one, and nudged at most once a week when close. */
    private fun notifyLevel(total: Int?, first: Boolean) {
        if (total == null) return
        val store = graph.store
        if (first || store.levelAnnounced < 0) {
            store.levelAnnounced = Levels.of(total).ordinal
            return
        }
        val now = System.currentTimeMillis()
        val before = store.levelAnnounced
        val decision = LevelRules.decide(before, total, store.levelNudgedAt, now)
        store.levelAnnounced = decision.announced
        if (decision.announced > before) store.levelReachedAt = now
        if (decision.nudged) store.levelNudgedAt = now
        val note = decision.note ?: return
        // While the app is open the banner on Updates says it; no notification on top.
        if (store.notifyLevel && !graph.inFront) graph.notifier.postLevel(note.title, note.text)
    }

    /** The phone's push token, so wake-ups reach it. */
    private suspend fun registerPush() {
        if (FirebaseApp.getApps(graph.context).isEmpty()) return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        store.pushToken = token
        if (store.pushTokenSent == token) return
        runCatching { api.saveDevice(token) }.onSuccess { store.pushTokenSent = token }
    }

    private fun handle(e: ApiException) {
        when (e.code) {
            "update_required", "consent_required" -> setGate("update")
            "account_suspended" -> setGate("suspended")
            else -> Log.w(TAG, "refresh failed: ${e.status} ${e.code}")
        }
    }

    private fun setGate(value: String) {
        store.gate = value
        _state.update { it.copy(gate = gateFrom(value)) }
    }

    private fun gateFrom(value: String?): Gate = when (value) {
        "update" -> Gate.UPDATE_NEEDED
        "suspended" -> Gate.SUSPENDED
        else -> Gate.NONE
    }

    private fun setupFrom(profile: Profile?, cards: CachedCards?, current: Setup? = null): Setup = when {
        current == Setup.FINDING -> Setup.FINDING
        profile?.sync?.enabled == true && (graph.book.hasContactsPermission() || graph.book.hasCallLogPermission()) -> Setup.READY
        cards?.takingPart == true && store.syncEnabledHint -> Setup.READY
        else -> Setup.NOT_ALLOWED
    }

    private companion object {
        const val TAG = "FoundWith"
        const val PAGE_SIZE = 200
        const val MAX_CACHED_CARDS = 2000
    }
}
