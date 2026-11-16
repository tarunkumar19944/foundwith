package app.foundwith.core

/** Someone the viewer knows who has a number: a candidate to ask "do you know who this is?". */
data class KnownPerson(
    val id: String,
    val name: String,
    val e164: String?,
    val inContacts: Boolean,
    val starred: Boolean = false,
    /** Calls with this person in the call log. */
    val calls: Int = 0,
)

/** The small grey hint under each name in the Ask list. */
enum class AskWhy(val hint: String) {
    TALK_OFTEN("In your contacts · you talk often"),
    FAVOURITE("In your contacts · favourite"),
    CONTACT("In your contacts"),
    FROM_CALLS("In your call log"),
}

/**
 * Who to ask first (agreed 17 Nov 2026: contacts before call-log people): contacts you talk to
 * most (3 or more calls), then favourites, then your other contacts A to Z, then people who are
 * only in your call log, most calls first.
 */
object AskOrder {
    const val TALK_OFTEN_CALLS = 3

    fun order(people: List<KnownPerson>): List<Pair<KnownPerson, AskWhy>> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { p: KnownPerson -> p.name }
        val seen = HashSet<String>()
        val unique = people.filter { seen.add(it.id) }
        val talk = unique.filter { it.inContacts && it.calls >= TALK_OFTEN_CALLS }
            .sortedWith(compareByDescending<KnownPerson> { it.calls }.then(byName))
        val rest = unique - talk.toSet()
        val favourites = rest.filter { it.starred && it.inContacts }.sortedWith(byName)
        val contacts = rest.filter { !it.starred && it.inContacts }.sortedWith(byName)
        val callsOnly = rest.filter { !it.inContacts }.sortedWith(compareByDescending<KnownPerson> { it.calls }.then(byName))
        return talk.map { it to AskWhy.TALK_OFTEN } + favourites.map { it to AskWhy.FAVOURITE } +
            contacts.map { it to AskWhy.CONTACT } + callsOnly.map { it to AskWhy.FROM_CALLS }
    }
}
