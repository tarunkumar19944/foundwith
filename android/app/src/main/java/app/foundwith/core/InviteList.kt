package app.foundwith.core

/** A contact as read from the phone: the number as typed, the name, and whether it's a favourite. */
data class PhoneContact(val raw: String, val name: String?, val starred: Boolean = false)

/** Someone worth inviting: a mobile number not yet on Found With. */
data class InviteCandidate(val e164: String, val name: String, val calls: Int, val starred: Boolean)

/**
 * Who to invite first: the people you talk to most come first (most calls in the call log), then
 * favourites, then by name. The messages themselves are in [Messages].
 */
object InviteList {
    private val CODE = Regex("""(?:^|[&?])invite=([A-HJ-NP-Za-hj-np-z2-9]{8})(?:&|$)""")

    /**
     * The invite code in a Google Play install referrer such as `invite=ABCD2345`, or null for
     * installs that didn't come from an invite link.
     */
    fun codeFrom(referrer: String?): String? =
        referrer?.let { CODE.find(it)?.groupValues?.get(1)?.uppercase() }

    /**
     * [calls] holds one raw number per call in the call log. Numbers in [onFoundWith] (E.164)
     * are left out, and so is anything that isn't an Indian mobile, since invites go by WhatsApp
     * or SMS. A number saved twice appears once, under its first name.
     */
    fun rank(
        contacts: List<PhoneContact>,
        calls: List<String>,
        onFoundWith: Set<String>,
    ): List<InviteCandidate> {
        val callCounts = HashMap<String, Int>()
        for (raw in calls) {
            val e164 = PhoneNumbers.normalise(raw) ?: continue
            callCounts[e164] = (callCounts[e164] ?: 0) + 1
        }
        val byNumber = LinkedHashMap<String, InviteCandidate>()
        for (contact in contacts) {
            val e164 = PhoneNumbers.normalise(contact.raw) ?: continue
            if (!PhoneNumbers.isIndianMobile(e164) || e164 in onFoundWith) continue
            val name = contact.name?.trim().orEmpty().ifEmpty { PhoneNumbers.display(e164) }
            val seen = byNumber[e164]
            byNumber[e164] = if (seen == null) {
                InviteCandidate(e164, name, callCounts[e164] ?: 0, contact.starred)
            } else {
                seen.copy(starred = seen.starred || contact.starred)
            }
        }
        return byNumber.values.sortedWith(
            compareByDescending<InviteCandidate> { it.calls }
                .thenByDescending { it.starred }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    /** The first word of a contact's name, for a friendly greeting; null for names that are just numbers. */
    fun firstName(name: String): String? {
        val word = name.trim().split(' ', '\t').firstOrNull().orEmpty()
        return word.takeIf { it.isNotEmpty() && it.any(Char::isLetter) }
    }
}
