package app.foundwith.core

/**
 * The messages WhoHas types into WhatsApp (or Messages) for the person to send. Nothing is
 * ever sent automatically. Rules:
 *  - They speak to the person receiving them ("you" is always the reader).
 *  - No greeting by name: names in India are kept as "Dr. Anand", "Plumber Raju", "Lakshmi Aunty",
 *    and "Hi Dr." reads wrong.
 *  - The number sits on its own line, so it can be tapped or copied in WhatsApp.
 *  - They never name who has a number, and every count is the real one (never rounded up).
 */
object Messages {
    /** "Do you know who this is?", sent to someone you know who has the number. No link: they're on WhoHas. */
    fun ask(e164: String, ownerName: String? = null, popularName: String? = null): String {
        val number = PhoneNumbers.display(e164)
        val owner = ownerName?.trim()?.takeIf { it.isNotEmpty() }
        if (owner != null) return "Hi, do you know $owner?\n$number"
        val popular = popularName?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Hi, do you know whose number this is?\n").append(number)
            if (popular != null) append("\nSome people know it as \"").append(popular).append("\".")
        }
    }

    /**
     * Tell (and Invite): sent to a contact who isn't on WhoHas. [others]: how many people on
     * WhoHas have their number apart from the sender (the count on the sender's card).
     */
    fun invite(others: Int, link: String, lowerBound: Boolean = false): String = when {
        others <= 0 -> "$PITCH Join here:\n$link"
        others == 1 && !lowerBound -> "Hi! I'm on WhoHas, and 1 more person there has your number. See who:\n$link"
        else -> "Hi! I'm on WhoHas, and ${HolderText.grouped(others)}${if (lowerBound) "+" else ""} more people there " +
            "have your number. See who they are:\n$link"
    }

    /** "Share your link" on the Invite friends page: to anyone, through any app. */
    fun inviteLink(link: String): String = "$PITCH Join here:\n$link"

    /**
     * Sharing a number with a friend. [name] is what the sender sees for it (null or the number
     * itself when it has no name). [others]: people on WhoHas who have it, not counting the sender.
     */
    fun share(name: String?, e164: String, others: Int, link: String, lowerBound: Boolean = false): String = buildString {
        val number = PhoneNumbers.display(e164)
        val title = name?.trim()?.takeIf { it.isNotEmpty() && it != number && !Naming.looksLikeNumber(it) }
        if (title != null) append(title).append('\n')
        append(number).append("\n\n")
        when {
            others <= 0 -> append("See who has any number on WhoHas:\n")
            others == 1 && !lowerBound -> append("1 other person on WhoHas has this number. See who:\n")
            else -> append(HolderText.grouped(others)).append(if (lowerBound) "+" else "")
                .append(" other people on WhoHas have this number. See who:\n")
        }
        append(link)
    }

    /** True with no one else on WhoHas: friends cover each other's unknown calls. */
    private const val PITCH =
        "Hi! I'm on WhoHas. If an unknown number calls either of us, we can see if the other knows them."
}
