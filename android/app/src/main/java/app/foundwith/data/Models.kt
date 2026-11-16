package app.foundwith.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The API's JSON, as described in docs/SPEC.md section 7. Unknown fields are ignored, so the
// server can add fields without breaking installed apps.

@Serializable
data class Holder(
    val id: String,
    /** Their WhoHas name; null until they choose one. */
    val name: String? = null,
    val known: Boolean = false,
    /** Set when the viewer knows this person: the reference of their number on the viewer's phone. */
    @SerialName("contact_ref") val contactRef: String? = null,
)

@Serializable
data class Card(
    val ref: String,
    @SerialName("in_contacts") val inContacts: Boolean = false,
    @SerialName("in_call_log") val inCallLog: Boolean = false,
    /** Everyone on WhoHas who has the number, never counting the viewer. */
    val total: Int = 0,
    @SerialName("total_is_lower_bound") val totalIsLowerBound: Boolean = false,
    val known: List<Holder> = emptyList(),
    val others: List<Holder> = emptyList(),
    val more: Int = 0,
    /** The owner's own WhoHas name ("name they gave"). */
    val name: String? = null,
    /** The name at least 3 people have for the number in their contacts ("what others call it"). */
    @SerialName("popular_name") val popularName: String? = null,
    /** How many people have that name for it ("5 people know it as …"); 0 when not sent. */
    @SerialName("popular_name_people") val popularNamePeople: Int = 0,
    /** How many of the holders the viewer knows; [known] lists at most 100. -1 from older servers. */
    @SerialName("known_count") val knownCount: Int = -1,
    /** The owner uses WhoHas (so: no Invite button, and Block is possible). */
    @SerialName("on_found_with") val onFoundWith: Boolean = false,
    @SerialName("owner_id") val ownerId: String? = null,
) {
    /** Everyone the viewer knows who has the number, including any beyond the listed ones. */
    val knownTotal: Int get() = if (knownCount >= 0) maxOf(knownCount, known.size) else known.size
}

@Serializable
data class CardsPage(
    val cards: List<Card> = emptyList(),
    @SerialName("total_cards") val totalCards: Int = 0,
    val next: String? = null,
    /** The viewer's own number: who has it. First page only. */
    val mine: Card? = null,
    /** False until the person allows WhoHas to use their phone. */
    @SerialName("taking_part") val takingPart: Boolean = true,
)

@Serializable
data class Quota(val limit: Int = 0, val left: Int = 0)

@Serializable
data class LookupResult(
    val ref: String,
    val number: String,
    val context: String = "search",
    val hidden: Boolean = false,
    val total: Int = 0,
    @SerialName("total_is_lower_bound") val totalIsLowerBound: Boolean = false,
    val known: List<Holder> = emptyList(),
    val others: List<Holder> = emptyList(),
    val more: Int = 0,
    val quota: Quota = Quota(),
    val name: String? = null,
    @SerialName("popular_name") val popularName: String? = null,
    @SerialName("popular_name_people") val popularNamePeople: Int = 0,
    @SerialName("known_count") val knownCount: Int = -1,
    @SerialName("on_found_with") val onFoundWith: Boolean = false,
    @SerialName("owner_id") val ownerId: String? = null,
    /** The owner took the number out of WhoHas (web opt-out). */
    @SerialName("taken_out") val takenOut: Boolean = false,
) {
    fun asCard(): Card = Card(ref = ref, total = total, totalIsLowerBound = totalIsLowerBound, known = known,
        others = others, more = more, name = name, popularName = popularName, popularNamePeople = popularNamePeople,
        knownCount = knownCount, onFoundWith = onFoundWith, ownerId = ownerId)
}

@Serializable
data class HoldersPage(
    val ref: String,
    val holders: List<Holder> = emptyList(),
    val next: String? = null,
    @SerialName("list_capped") val listCapped: Boolean = false,
)

@Serializable
data class SyncStats(
    val numbers: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val skipped: Int = 0,
    val deferred: Int = 0,
)

/** `refs` maps each stored number (E.164) to its reference. */
@Serializable
data class SyncResult(val refs: Map<String, String> = emptyMap(), val stats: SyncStats = SyncStats())

@Serializable
data class SyncState(
    val enabled: Boolean = false,
    @SerialName("consent_version") val consentVersion: String? = null,
    @SerialName("current_consent_version") val currentConsentVersion: String = "",
    @SerialName("consent_current") val consentCurrent: Boolean = false,
    @SerialName("first_sync_at") val firstSyncAt: String? = null,
)

@Serializable
data class Quotas(val search: Quota = Quota(), val call: Quota = Quota())

@Serializable
data class Profile(
    val id: String,
    /** Null until the person chooses a name. */
    val name: String? = null,
    val phone: String? = null,
    val suspended: Boolean = false,
    @SerialName("number_opted_out") val numberOptedOut: Boolean = false,
    val visibility: String = "known",
    @SerialName("evening_note") val eveningNote: Boolean = true,
    val sync: SyncState = SyncState(),
    val quota: Quotas = Quotas(),
    /** People on WhoHas who tapped Tell for this person's number, newest first (at most 3 names). */
    @SerialName("invited_by") val invitedBy: List<String> = emptyList(),
    @SerialName("invited_by_count") val invitedByCount: Int = 0,
)

@Serializable
data class InviteInfo(
    val code: String = "",
    val link: String = "",
    /** How many people joined with this person's link. */
    val joined: Int = 0,
)

@Serializable
data class Circle(
    val members: Int = 0,
    /** References of the viewer's numbers that belong to people on WhoHas. */
    @SerialName("member_refs") val memberRefs: List<String> = emptyList(),
    val goal: Int = 20,
    val invite: InviteInfo = InviteInfo(),
)

@Serializable
data class InviteRedeemed(val redeemed: Boolean = false)

@Serializable
data class DeleteResult(val deleted: Boolean = false)

@Serializable
data class BlockEntry(
    val id: Long,
    val name: String? = null,
    /** The blocked person's number, as a reference: the same as the card for that number. */
    val ref: String? = null,
)

@Serializable
data class BlocksList(val blocks: List<BlockEntry> = emptyList())

@Serializable
data class DataNumbers(val contacts: Int = 0, @SerialName("call_log") val callLog: Int = 0, val hidden: Int = 0)

/** What WhoHas stores about the person, in counts, for "Your data and account". */
@Serializable
data class MyData(
    val name: String? = null,
    val phone: String? = null,
    val numbers: DataNumbers = DataNumbers(),
    val blocks: Int = 0,
)

@Serializable
data class ApiErrorDetail(val code: String, val message: String, val details: JsonObject? = null)

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail)
