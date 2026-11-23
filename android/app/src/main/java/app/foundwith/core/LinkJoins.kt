package app.foundwith.core

/**
 * People who joined WhoHas with your invite link (designed with Y, 23 Nov 2026): the words on your
 * own card, in Updates, on Invite friends and in the notification. The server tells the phone who
 * joined with the link (as references); names come only from the person's own phone.
 */
object LinkJoins {
    /** "Ravi Kumar", "Ravi and Asha", "Ravi and 2 others", "Someone", "3 people". [names]: null for someone not on this phone. */
    fun who(names: List<String?>): String {
        val known = names.filterNotNull().filter { it.isNotBlank() }
        val n = names.size
        fun first(name: String) = InviteList.firstName(name) ?: name
        return when {
            n <= 0 -> ""
            known.isEmpty() -> if (n == 1) "Someone" else "$n people"
            n == 1 -> known[0]
            n == 2 && known.size == 2 -> "${first(known[0])} and ${first(known[1])}"
            else -> "${first(known[0])} and ${n - 1} ${if (n - 1 == 1) "other" else "others"}"
        }
    }

    /** "Ravi Kumar joined with your link". */
    fun title(names: List<String?>): String = who(names) + " joined with your link"

    /** On Invite friends: "Ravi and Asha already joined with your link." */
    fun already(names: List<String?>): String = who(names) + " already joined with your link."

    /**
     * Under the people who joined, on your own card: how many of them have your number, counted
     * only from what WhoHas shows you ([withYours]), so it never claims more than is known.
     */
    fun rowLine(joined: Int, withYours: Int): String = when {
        withYours <= 0 -> "Each one who has your number adds 1."
        withYours >= joined && joined == 1 -> "Has your number: +1"
        withYours >= joined && joined == 2 -> "Both have your number: +2"
        withYours >= joined -> "All $joined have your number: +$joined"
        else -> "$withYours of them ${if (withYours == 1) "has" else "have"} your number: +$withYours"
    }

    /** The notification's text: "Has your number. 9 people have it now: 1 more and you’re Popular." */
    fun noteText(hasYours: Boolean, total: Int): String {
        val step = Levels.step(total)
        val next = step?.let { "${HolderText.grouped(it.left)} more and you’re ${it.next.label}." }
        return if (hasYours) {
            val now = if (total == 1) "1 person has it now" else "${HolderText.grouped(total)} people have it now"
            "Has your number. $now" + (next?.let { ": $it" } ?: ".")
        } else {
            val count = if (total <= 0) "No one on WhoHas has your number yet." else HolderText.haveYours(total) + "."
            count + (next?.let { " $it" } ?: "")
        }
    }

    /** In Updates, under someone who joined with your link: "1 more and you’re Popular"; null at the top. */
    fun levelLine(total: Int): String? = Levels.step(total)?.let { "${HolderText.grouped(it.left)} more and you’re ${it.next.label}" }

    /**
     * How many could add to your count: "38 people you call aren’t on WhoHas" ([called]: from the
     * call log), or "38 of your contacts aren’t on WhoHas" when there are no calls to go by.
     */
    fun reach(count: Int, called: Boolean): String = when {
        called && count == 1 -> "1 person you call isn’t on WhoHas"
        called -> "${HolderText.grouped(count)} people you call aren’t on WhoHas"
        count == 1 -> "1 of your contacts isn’t on WhoHas"
        else -> "${HolderText.grouped(count)} of your contacts aren’t on WhoHas"
    }

    const val REACH_LINE = "They likely have your number. Each one who joins can add 1."

    /** On Invite friends: "They likely have your number. Each one who joins can add 1 to your 8." */
    fun reachInvite(total: Int): String =
        if (total <= 0) REACH_LINE else "They likely have your number. Each one who joins can add 1 to your ${HolderText.grouped(total)}."
}
