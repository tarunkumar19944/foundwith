package app.foundwith.data

import android.content.Context
import app.foundwith.core.ChangeLog
import app.foundwith.core.CountChange
import app.foundwith.core.KnownGain
import app.foundwith.core.HealthDay
import app.foundwith.core.Snapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The last cards loaded, so Home and the incoming-call panel can show something instantly. */
@Serializable
data class CachedCards(
    @SerialName("saved_at") val savedAt: Long,
    @SerialName("total_cards") val totalCards: Int,
    val cards: List<Card>,
    /** The viewer's own number. */
    val mine: Card? = null,
    val circle: Circle? = null,
    /** False until the person allows WhoHas to use their phone. */
    @SerialName("taking_part") val takingPart: Boolean = true,
)

@Serializable
private data class ChangeFile(
    val baseline: Map<String, Int> = emptyMap(),
    val changes: List<ChangeRow> = emptyList(),
    val members: List<String>? = null,
    val joined: Map<String, Long> = emptyMap(),
    val known: Map<String, List<String>>? = null,
    val gains: List<GainRow> = emptyList(),
)

@Serializable
private data class GainRow(val ref: String, val ids: List<String>, val at: Long)

@Serializable
private data class ChangeRow(val ref: String, val before: Int, val now: Int, val at: Long)

@Serializable
private data class CallsFile(
    /** Call log ids already handled after a call, newest last. */
    val handled: List<Long> = emptyList(),
    /** When calls were only reached after the hour was up: the phone is stopping the app. */
    @SerialName("late_at") val lateAt: List<Long> = emptyList(),
    val health: HealthRow? = null,
)

@Serializable
private data class HealthRow(val day: String, val seen: Int, val checked: Int)

/** What the viewer calls a number, for the cards. */
@Serializable
data class NumberLabel(val e164: String, val name: String? = null)

/**
 * Maps between the server's references and the viewer's own numbers. Built from sync replies
 * and the phone's contacts; never leaves the phone.
 */
@Serializable
data class NumberIndex(
    @SerialName("by_ref") val byRef: Map<String, NumberLabel> = emptyMap(),
    @SerialName("ref_by_e164") val refByE164: Map<String, String> = emptyMap(),
)

@Serializable
private data class SnapshotFile(val contacts: List<String> = emptyList(), @SerialName("call_log") val callLog: List<String> = emptyList())

/**
 * Everything the app keeps on the phone, in its private storage (excluded from backups).
 * Small settings live in SharedPreferences; cards, labels and the sync snapshot in JSON files
 * written atomically.
 */
/** A lookup kept for a call's card, with when it was made. */
@Serializable
data class SavedLookup(val at: Long, val result: LookupResult)

class LocalStore(context: Context) {
    private val dir = File(context.filesDir, "foundwith").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("foundwith", Context.MODE_PRIVATE)
    /** About how this copy of the app was installed. Kept through sign-out, unlike [prefs]. */
    private val install = context.getSharedPreferences("foundwith_install", Context.MODE_PRIVATE)
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lock = Mutex()

    @Volatile private var cards: CachedCards? = null
    @Volatile private var index: NumberIndex? = null

    var devPhone: String?
        get() = prefs.getString("dev_phone", null)
        set(value) { prefs.edit().putString("dev_phone", value).apply() }

    /**
     * A random key put in WhoHas's own "open this number" notifications. MainActivity is exported (it's
     * the launcher), so it opens a number only when the key matches: another app can't make it look
     * numbers up and use the person's daily searches.
     */
    val openKey: String
        get() = prefs.getString("open_key", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("open_key", it).apply()
        }

    /** Whether the person has sync on, as last seen from the server (used before the first network call). */
    var syncEnabledHint: Boolean
        get() = prefs.getBoolean("sync_enabled", false)
        set(value) { prefs.edit().putBoolean("sync_enabled", value).apply() }

    /** "everyone" or "known", from the profile: decides what the consent screen says. */
    var visibility: String
        get() = prefs.getString("visibility", "known") ?: "known"
        set(value) { prefs.edit().putString("visibility", value).apply() }

    /** Whether Google Play has been asked which invite link, if any, the app was installed from. */
    var referrerChecked: Boolean
        get() = install.getBoolean("referrer_checked", false)
        set(value) { install.edit().putBoolean("referrer_checked", value).apply() }

    /** An invite code from the install link, waiting to be redeemed once the person has signed up. */
    var pendingInvite: String?
        get() = install.getString("pending_invite", null)
        set(value) { install.edit().putString("pending_invite", value).apply() }

    var lastFullSyncAt: Long
        get() = prefs.getLong("last_full_sync", 0L)
        set(value) { prefs.edit().putLong("last_full_sync", value).apply() }

    /** When the person last had Home open: Updates since then get a green dot. */
    var lastOpenedAt: Long
        get() = prefs.getLong("last_opened", 0L)
        set(value) { prefs.edit().putLong("last_opened", value).apply() }

    /**
     * When the person last looked at Updates (22 Nov 2026): newer updates count on the Updates tab's
     * badge and get a dot. Before this existed, the last time the app was left stood in for it.
     */
    var updatesSeenAt: Long
        get() = prefs.getLong("updates_seen", 0L).takeIf { it > 0 } ?: lastOpenedAt
        set(value) { prefs.edit().putLong("updates_seen", value).apply() }

    /** When each number was last told from the Tell card or the Invite page (E.164 -> millis). */
    fun toldAt(): Map<String, Long> = prefs.getStringSet("told", emptySet()).orEmpty().mapNotNull { entry ->
        val (number, at) = entry.split('@').takeIf { it.size == 2 } ?: return@mapNotNull null
        at.toLongOrNull()?.let { number to it }
    }.toMap()

    fun markTold(e164: String, at: Long = System.currentTimeMillis()) {
        val kept = toldAt().filterKeys { it != e164 }.filterValues { at - it < 90L * 24 * 3600_000 }
        prefs.edit().putStringSet("told", (kept + (e164 to at)).map { "${it.key}@${it.value}" }.toSet()).apply()
    }

    /** The number rules (PhoneNumbers.RULES) this phone last sent its numbers under. */
    var numberRules: Int
        get() = prefs.getInt("number_rules", 1)
        set(value) { prefs.edit().putInt("number_rules", value).apply() }

    /** When the phone last rebuilt its map of the person's numbers because a card named someone it couldn't place. */
    var indexRepairedAt: Long
        get() = prefs.getLong("index_repaired", 0L)
        set(value) { prefs.edit().putLong("index_repaired", value).apply() }

    /** When the person tapped Hide on the Tell card (it stays away for 7 days). */
    var tellHiddenAt: Long
        get() = prefs.getLong("tell_hidden", 0L)
        set(value) { prefs.edit().putLong("tell_hidden", value).apply() }

    /** The "Turn on notifications" card was answered or put off with Later. */
    var notifAsked: Boolean
        get() = prefs.getBoolean("notif_asked", false)
        set(value) { prefs.edit().putBoolean("notif_asked", value).apply() }

    /** Whether names have shown on Home at least once: the name and notification cards wait for it. */
    var firstNamesSeen: Boolean
        get() = prefs.getBoolean("first_names_seen", false)
        set(value) { prefs.edit().putBoolean("first_names_seen", value).apply() }

    /** The one-time "Add your name" card has been answered or dismissed. */
    var nameAsked: Boolean
        get() = prefs.getBoolean("name_asked", false)
        set(value) { prefs.edit().putBoolean("name_asked", value).apply() }

    /** The person tapped Allow at least once (they accepted the explanation on the Allow card). */
    var allowTapped: Boolean
        get() = prefs.getBoolean("allow_tapped", false)
        set(value) { prefs.edit().putBoolean("allow_tapped", value).apply() }

    /** How many times Android's permission question was answered "no": after two, Android stops asking. */
    fun denials(permission: String): Int = prefs.getInt("denied_$permission", 0)
    fun addDenial(permission: String) = prefs.edit().putInt("denied_$permission", denials(permission) + 1).apply()
    fun clearDenials(permission: String) = prefs.edit().remove("denied_$permission").apply()

    /** Notification switches (22 Nov 2026), all on by default. */
    var notifyUnknownCalls: Boolean
        get() = prefs.getBoolean("n_unknown_calls", true)
        set(value) { prefs.edit().putBoolean("n_unknown_calls", value).apply() }
    var notifyOtherCalls: Boolean
        get() = prefs.getBoolean("n_other_calls", true)
        set(value) { prefs.edit().putBoolean("n_other_calls", value).apply() }
    var notifyChanges: Boolean
        get() = prefs.getBoolean("n_changes", true)
        set(value) { prefs.edit().putBoolean("n_changes", value).apply() }
    var notifyJoined: Boolean
        get() = prefs.getBoolean("n_joined", true)
        set(value) { prefs.edit().putBoolean("n_joined", value).apply() }
    var notifyLevel: Boolean
        get() = prefs.getBoolean("n_level", true)
        set(value) { prefs.edit().putBoolean("n_level", value).apply() }

    /** The highest level already told about (0 not popular, 1 popular, 2 highly popular); -1 until first seen. */
    var levelAnnounced: Int
        get() = prefs.getInt("level_announced", -1)
        set(value) { prefs.edit().putInt("level_announced", value).apply() }
    /** When a new level was reached: Updates shows "You're Popular now" at the top for one day. */
    var levelReachedAt: Long
        get() = prefs.getLong("level_reached_at", 0L)
        set(value) { prefs.edit().putLong("level_reached_at", value).apply() }
    fun levelReachedRecently(now: Long): Boolean = now - levelReachedAt in 0 until 24 * 60 * 60 * 1000L

    var levelNudgedAt: Long
        get() = prefs.getLong("level_nudged_at", 0L)
        set(value) { prefs.edit().putLong("level_nudged_at", value).apply() }

    /** The count WhoHas last told about per caller, so a repeat call is told only when more people have it. */
    fun lastNotified(e164: String): Int? = prefs.getInt("told_$e164", -1).takeIf { it >= 0 }
    fun setLastNotified(e164: String, total: Int) {
        val keys = prefs.getString("told_keys", "").orEmpty().split(',').filter { it.isNotEmpty() && it != e164 } + e164
        val drop = keys.dropLast(MAX_TOLD)
        prefs.edit().apply {
            putInt("told_$e164", total)
            drop.forEach { remove("told_$it") }
            putString("told_keys", keys.takeLast(MAX_TOLD).joinToString(","))
        }.apply()
    }

    /** When Found With started watching calls (the person allowed it): calls before are history. */
    var callsWatchedSince: Long
        get() = prefs.getLong("calls_watched_since", 0L)
        set(value) { prefs.edit().putLong("calls_watched_since", value).apply() }

    /** The phone-settings banner was closed; it comes back if calls are missed again later. */
    var fixDismissedAt: Long
        get() = prefs.getLong("fix_dismissed", 0L)
        set(value) { prefs.edit().putLong("fix_dismissed", value).apply() }

    var pushToken: String?
        get() = prefs.getString("push_token", null)
        set(value) { prefs.edit().putString("push_token", value).apply() }

    /** The push token the server has; differs from [pushToken] until it has been sent. */
    var pushTokenSent: String?
        get() = prefs.getString("push_token_sent", null)
        set(value) { prefs.edit().putString("push_token_sent", value).apply() }

    /** The last profile, so the app can open with the right screen before the network answers. */
    var profileJson: String?
        get() = prefs.getString("profile", null)
        set(value) { prefs.edit().putString("profile", value).apply() }

    /** The blocked people, for leaving them out of lists and notifications. */
    var blockedRefs: Set<String>
        get() = prefs.getStringSet("blocked_refs", emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet("blocked_refs", value).apply() }

    /** The server said this version is too old, or the account is suspended: shown until fixed. */
    var gate: String?
        get() = prefs.getString("gate", null)
        set(value) { prefs.edit().putString("gate", value).apply() }

    // ---------------------------------------------------------------- what changed

    suspend fun changeLog(): ChangeLog? = read("changes.json", ChangeFile.serializer())?.let { file ->
        ChangeLog(
            file.baseline,
            file.changes.associate { it.ref to CountChange(it.ref, it.before, it.now, it.at) },
            file.members?.toSet(),
            file.joined,
            file.known?.mapValues { it.value.toSet() },
            file.gains.associate { it.ref to KnownGain(it.ref, it.ids, it.at) },
        )
    }

    suspend fun saveChangeLog(value: ChangeLog) = write("changes.json", ChangeFile.serializer(),
        ChangeFile(
            value.baseline,
            value.changes.values.map { ChangeRow(it.ref, it.before, it.now, it.at) },
            value.members?.toList(),
            value.joined,
            value.known?.mapValues { it.value.toList() },
            value.gains.values.map { GainRow(it.ref, it.ids, it.at) },
        ))

    // ---------------------------------------------------------------- after calls

    /** Call ids already handled, the times calls were reached too late, and today's health. */
    suspend fun callState(): Triple<Set<Long>, List<Long>, HealthDay?> {
        val file = read("calls.json", CallsFile.serializer()) ?: CallsFile()
        return Triple(file.handled.toSet(), file.lateAt, file.health?.let { HealthDay(it.day, it.seen, it.checked) })
    }

    suspend fun saveCallState(handled: Collection<Long>, lateAt: List<Long>, health: HealthDay?) =
        write("calls.json", CallsFile.serializer(), CallsFile(
            handled = handled.sorted().takeLast(MAX_HANDLED),
            lateAt = lateAt.sorted().takeLast(50),
            health = health?.let { HealthRow(it.day, it.callsSeen, it.callsChecked) },
        ))

    // ---------------------------------------------------------------- cards

    suspend fun cachedCards(): CachedCards? {
        cards?.let { return it }
        return read("cards.json", CachedCards.serializer())?.also { cards = it }
    }

    suspend fun saveCards(value: CachedCards) {
        cards = value
        write("cards.json", CachedCards.serializer(), value)
    }

    // ---------------------------------------------------------------- labels

    suspend fun index(): NumberIndex {
        index?.let { return it }
        return (read("index.json", NumberIndex.serializer()) ?: NumberIndex()).also { index = it }
    }

    suspend fun saveIndex(value: NumberIndex) {
        index = value
        write("index.json", NumberIndex.serializer(), value)
    }

    // ---------------------------------------------------------------- sync snapshot

    suspend fun snapshot(): Snapshot? =
        read("snapshot.json", SnapshotFile.serializer())?.let { Snapshot(it.contacts.toSet(), it.callLog.toSet()) }

    suspend fun saveSnapshot(value: Snapshot) =
        write("snapshot.json", SnapshotFile.serializer(), SnapshotFile(value.contacts.sorted(), value.callLog.sorted()))

    suspend fun clearSnapshot() = lock.withLock { withContext(Dispatchers.IO) { File(dir, "snapshot.json").delete() } }

    /**
     * The lookup made for a call's notification, so tapping the notification opens the card with the
     * same count straight away instead of looking the number up again. The last 50, for one day.
     */
    suspend fun callLookup(e164: String, now: Long = System.currentTimeMillis()): SavedLookup? =
        read(CALL_LOOKUPS, savedLookups)?.get(e164)?.takeIf { now - it.at in 0..CALL_LOOKUP_KEEP_MS }

    suspend fun saveCallLookup(e164: String, result: LookupResult, now: Long = System.currentTimeMillis()) {
        val kept = read(CALL_LOOKUPS, savedLookups).orEmpty().filter { (k, v) -> k != e164 && now - v.at in 0..CALL_LOOKUP_KEEP_MS }
        val all = (kept.entries.sortedBy { it.value.at }.map { it.key to it.value } + (e164 to SavedLookup(now, result))).takeLast(MAX_CALL_LOOKUPS)
        write(CALL_LOOKUPS, savedLookups, all.toMap())
    }

    private val savedLookups = MapSerializer(String.serializer(), SavedLookup.serializer())

    private companion object {
        private const val MAX_TOLD = 200
        const val MAX_HANDLED = 1000
        const val CALL_LOOKUPS = "call_lookups.json"
        const val MAX_CALL_LOOKUPS = 50
        const val CALL_LOOKUP_KEEP_MS = 24 * 60 * 60 * 1000L
    }

    /** Forget everything: used when the person signs out or deletes their account. */
    suspend fun clear() = lock.withLock {
        cards = null
        index = null
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { it.delete() }
            prefs.edit().clear().apply()
        }
    }

    // ---------------------------------------------------------------- files

    private suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = lock.withLock {
        withContext(Dispatchers.IO) {
            val file = File(dir, name)
            if (!file.exists()) return@withContext null
            runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
        }
    }

    private suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T) = lock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            if (!tmp.renameTo(File(dir, name))) {
                File(dir, name).delete()
                tmp.renameTo(File(dir, name))
            }
        }
    }
}
