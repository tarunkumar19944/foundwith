package app.foundwith.core

/** Where the name shown for a number came from. */
enum class NameSource { CONTACT, THEIR_OWN, POPULAR, NUMBER }

/** The name to show for a number, with its small grey tag when it isn't the viewer's own. */
data class ShownName(val text: String, val source: NameSource) {
    val tag: String?
        get() = when (source) {
            NameSource.THEIR_OWN -> "their own name"
            NameSource.POPULAR -> "what others call it"
            else -> null
        }
    val isNumber: Boolean get() = source == NameSource.NUMBER
}

/**
 * Every number looks the same everywhere (Home, search, after calls). Its name, in order:
 *  1. the viewer's own contact name,
 *  2. the name the owner typed into WhoHas ("their own name"),
 *  3. the name at least 3 people have for it in their contacts ("what others call it"),
 *  4. the number.
 * Names are shown exactly as saved: "Dr. Anand" and "Lakshmi Aunty" are never shortened.
 */
object Naming {
    fun of(contactName: String?, ownerName: String?, popularName: String?, e164: String?, raw: String? = null): ShownName {
        contactName?.trim()?.takeIf { it.isNotEmpty() && !looksLikeNumber(it) }?.let { return ShownName(it, NameSource.CONTACT) }
        ownerName?.trim()?.takeIf { it.isNotEmpty() }?.let { return ShownName(it, NameSource.THEIR_OWN) }
        popularName?.trim()?.takeIf { it.isNotEmpty() }?.let { return ShownName(it, NameSource.POPULAR) }
        val number = e164?.let { PhoneNumbers.display(it) } ?: raw?.trim().orEmpty()
        return ShownName(number.ifEmpty { "Unknown number" }, NameSource.NUMBER)
    }

    /** A contact "name" that is really the number (phones fill it in when no name was typed). */
    fun looksLikeNumber(name: String): Boolean = name.none { it.isLetter() }

    /**
     * How a person who has the number is shown on a card: the viewer's name for them, else their
     * own WhoHas name, else their number. [contactRef] people the viewer knows always have one of these.
     */
    fun person(contactName: String?, ownName: String?, e164: String?): String =
        contactName?.trim()?.takeIf { it.isNotEmpty() && !looksLikeNumber(it) }
            ?: ownName?.trim()?.takeIf { it.isNotEmpty() }
            ?: e164?.let { PhoneNumbers.display(it) }
            ?: "Someone you know"
}
