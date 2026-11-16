package app.foundwith.core

/**
 * The messages Found With types into WhatsApp (or Messages) for the person to send. Nothing is
 * ever sent automatically. Rules:
 *  - They speak to the person receiving them ("you" is always the reader).
 *  - No greeting by name: names in India are saved as "Dr. Anand", "Plumber Raju", "Lakshmi Aunty",
 *    and "Hi Dr." reads wrong.
 *  - The number sits on its own line, so it can be tapped or copied in WhatsApp.
 *  - They never name who has a number. Counts never include the reader's own copy of it ("other … too").
 */
object Messages {
    /** "Do you know who this is?", sent to someone you know who has the number. No link: they're on Found With. */
    fun ask(e164: String, ownerName: String? = null, popularName: String? = null): String {
        val number = PhoneNumbers.display(e164)
        val owner = ownerName?.trim()?.takeIf { it.isNotEmpty() }
        if (owner != null) return "Hi, do you know $owner?\n$number"
        val popular = popularName?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Hi, do you know whose number this is?\n").append(number)
            if (popular != null) append("\nIt shows as ").append(popular).append('.')
        }
    }

    /**
     * Inviting a contact who isn't on Found With. [others]: how many people on Found With have
     * their number, not counting the sender (the count on the sender's card).
     */
    fun invite(others: Int, link: String, lowerBound: Boolean = false): String = when {
        others <= 0 -> "Hi, $PITCH Join here:\n$link"
        others == 1 && !lowerBound -> "Hi, I'm using Found With. 1 other person on Found With has your number too. " +
            "See if you know them:\n$link"
        else -> "Hi, I'm using Found With. ${HolderText.grouped(others)}${if (lowerBound) "+" else ""} other people on Found With " +
            "have your number too. See which of them you know:\n$link"
    }

    /** "Share your link" on the Invite friends page: to anyone, through any app. */
    fun inviteLink(link: String): String = "$PITCH Join here:\n$link"

    /**
     * Sharing a number with a friend. [name] is what the sender sees for it (null or the number
     * itself when it has no name). [others]: people on Found With who have it, not counting the sender.
     */
    fun share(name: String?, e164: String, others: Int, link: String, lowerBound: Boolean = false): String = buildString {
        val number = PhoneNumbers.display(e164)
        val title = name?.trim()?.takeIf { it.isNotEmpty() && it != number && !Naming.looksLikeNumber(it) }
        if (title != null) append(title).append('\n')
        append(number).append("\n\n")
        when {
            others <= 0 -> append("See who has any number:\n")
            others == 1 && !lowerBound -> append("1 other person on Found With has this number too. See if you know them:\n")
            else -> append(HolderText.grouped(others)).append(if (lowerBound) "+" else "")
                .append(" other people on Found With have this number too. See which of them you know:\n")
        }
        append(link)
    }

    private const val PITCH =
        "I'm using Found With. When an unknown number calls, it shows which people you know have that number."
}
