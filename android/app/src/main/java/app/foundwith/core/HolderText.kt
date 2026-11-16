package app.foundwith.core

/** Someone shown on a card or in a notification, with the name the viewer should see. */
data class ShownPerson(val name: String, val known: Boolean)

/**
 * Every line that says who has a number, so WhoHas reads the same everywhere. The rules
 * (docs/WHOHAS_COPY.md): the number comes first, "has" means contacts or call log (never
 * "saved"), "you know" means someone in your contacts or call log, and counts are always real.
 * Counts never include the viewer.
 */
object HolderText {
    const val POPULAR_FROM = 10
    const val HIGHLY_POPULAR_FROM = 50

    /** Nobody on WhoHas has the number. */
    const val NO_ONE = "No one on WhoHas has this number"
    /** A number on the viewer's own phone that nobody else has: "no one" alone would read as wrong. */
    const val NO_ONE_ELSE = "No one else on WhoHas has it yet"
    /** The line under the 10+ labels. */
    const val LABEL_HINT = "Often a business, or someone who calls a lot of people"

    /** "1 person has this number", "12 people have this number", "10,000+ people have this number". */
    fun haveThis(total: Int, lowerBound: Boolean = false): String =
        count(total, lowerBound) + if (total == 1 && !lowerBound) " has this number" else " have this number"

    /** "1 person has it", "5 people have it". */
    fun haveIt(total: Int, lowerBound: Boolean = false): String =
        count(total, lowerBound) + if (total == 1 && !lowerBound) " has it" else " have it"

    /** Your own number: "1 person has your number", "23 people have your number". */
    fun haveYours(total: Int, lowerBound: Boolean = false): String =
        count(total, lowerBound) + if (total == 1 && !lowerBound) " has your number" else " have your number"

    /**
     * A call row and a call notification, about the caller: "Known to Priya", "Known to Priya and
     * Meena", "Known to Priya, Meena and 10 more". [known] are the people the viewer knows, in the
     * order to show them; "more" counts everyone else who has it. With no one known: "4 people have
     * this number". With nobody: [NO_ONE_ELSE] for a number on the viewer's phone, else [NO_ONE].
     */
    fun knownTo(known: List<String>, total: Int, lowerBound: Boolean = false, onYourPhone: Boolean = false, max: Int = 2): String {
        if (total <= 0) return if (onYourPhone) NO_ONE_ELSE else NO_ONE
        val shown = known.take(max)
        if (shown.isEmpty()) return haveThis(total, lowerBound)
        val rest = total - shown.size
        if (rest <= 0 && !lowerBound) return "Known to " + joined(shown)
        return "Known to " + shown.joinToString(", ") + " and " + grouped(rest.coerceAtLeast(1)) + (if (lowerBound) "+" else "") + " more"
    }

    /**
     * A contact row and a search result: "5 people have it · You know Priya and Meena",
     * "14 people have it · You know Meena, Deepa and 4 more", "1 person has it". [knownCount] is
     * how many of them the viewer knows (the names may be fewer).
     */
    fun haveItLine(known: List<String>, knownCount: Int, total: Int, lowerBound: Boolean = false, onYourPhone: Boolean = false): String {
        if (total <= 0) return if (onYourPhone) NO_ONE_ELSE else NO_ONE
        val head = haveIt(total, lowerBound)
        val names = youKnowNames(known, knownCount) ?: return head
        return "$head · $names"
    }

    /** "You know Priya", "You know Priya and Meena", "You know Priya, Meena and 4 more"; null when no one. */
    fun youKnowNames(known: List<String>, knownCount: Int = known.size, max: Int = 2): String? {
        val shown = known.take(max)
        if (shown.isEmpty()) return null
        val rest = knownCount.coerceAtLeast(shown.size) - shown.size
        if (rest <= 0) return "You know " + joined(shown)
        return "You know " + shown.joinToString(", ") + " and " + grouped(rest) + " more"
    }

    /** The card's second line: "You know 3 of them", "You know 1 of them", "You don't know any of them". */
    fun youKnowCount(knownCount: Int): String =
        if (knownCount <= 0) "You don't know any of them" else "You know ${grouped(knownCount)} of them"

    /** "Many people have this number" (10 to 49) or "Lots of people have this number" (50 and up). None under 10. */
    fun label(total: Int, lowerBound: Boolean = false): String? = when {
        total >= HIGHLY_POPULAR_FROM || (lowerBound && total >= POPULAR_FROM) -> "Lots of people have this number"
        total >= POPULAR_FROM -> "Many people have this number"
        else -> null
    }

    /** "1 more person", "3 more people". */
    fun more(n: Int): String = if (n == 1) "1 more person" else "${grouped(n)} more people"

    /** An Updates row and the after-call note for a contact: "3 more people have this number · now 12". */
    fun change(total: Int, delta: Int): String =
        "${more(delta)} ${if (delta == 1) "has" else "have"} this number · now ${grouped(total)}"

    /** The after-call note for a saved contact: "3 more people have Ravi's number · now 12". */
    fun changeFor(name: String, total: Int, delta: Int): String =
        "${more(delta)} ${if (delta == 1) "has" else "have"} $name's number · now ${grouped(total)}"

    /** Your own number went up: "1 more person has your number", "2 more people have your number". */
    fun mineUp(delta: Int): String = "${more(delta)} ${if (delta == 1) "has" else "have"} your number"

    /** The chip on a card that went up today: "+3 today". */
    fun today(delta: Int): String = "+${grouped(delta)} today"

    /** The chip on your own number: "+5 this week". */
    fun thisWeek(delta: Int): String = "+${grouped(delta)} this week"

    /** A count that crossed 10 or 50 since it was last seen: the Updates line, else null. */
    fun crossed(before: Int, now: Int): String? {
        val was = label(before)
        val isNow = label(now) ?: return null
        return if (isNow != was) "$isNow now" else null
    }

    /** The "What does this mean?" sheet, as (bold, rest) lines. */
    fun meaning(total: Int, knownCount: Int, lowerBound: Boolean = false, mine: Boolean = false): List<Pair<String, String>> {
        if (total <= 0) {
            return listOf((if (mine) "No one on WhoHas has your number yet." else "No one on WhoHas has this number yet.") to
                "When someone who has it joins, it shows here.")
        }
        val lines = mutableListOf<Pair<String, String>>()
        lines += ((if (mine) haveYours(total, lowerBound) else haveThis(total, lowerBound)) + ".") to "It's in their contacts or call log."
        if (knownCount > 0) lines += "${youKnowCount(knownCount)}." to "They're in your contacts or call log, so you see their names."
        val unknown = total - knownCount
        if (unknown > 0) lines += "${grouped(unknown)}${if (lowerBound) "+" else ""} you don't know." to "You only see how many."
        if (!mine) label(total, lowerBound)?.let { lines += "${it.substringBefore(" have this number")} have it." to "It's often a business, or someone who calls a lot of people." }
        return lines
    }

    /** "1 person", "12 people", "10,000+ people". */
    fun count(total: Int, lowerBound: Boolean = false): String =
        grouped(total) + (if (lowerBound) "+" else "") + if (total == 1 && !lowerBound) " person" else " people"

    private fun joined(names: List<String>): String =
        if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()

    /** Indian digit grouping: 1,000 / 12,345 / 1,22,000 / 10,00,000. */
    fun grouped(n: Int): String {
        if (n < 0) return "-" + grouped(-n)
        val s = n.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        var head = s.dropLast(3)
        val parts = ArrayDeque<String>()
        while (head.length > 2) {
            parts.addFirst(head.takeLast(2))
            head = head.dropLast(2)
        }
        if (head.isNotEmpty()) parts.addFirst(head)
        return parts.joinToString(",") + "," + last3
    }

    /** Two letters for an avatar: "Ravi Kumar" -> "RK", "Priya" -> "P", "Dr. Anand" -> "DA". */
    fun initials(name: String): String {
        val words = name.replace(Regex("""\(.*?\)"""), " ")
            .split(Regex("""[^\p{L}\p{M}]+"""))   // keep vowel signs: they are part of words in Indian scripts
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return "#"
        val first = words.first().substring(0, words.first().offsetByCodePoints(0, 1))
        if (words.size == 1) return first.uppercase()
        val lastWord = words.last()
        return (first + lastWord.substring(0, lastWord.offsetByCodePoints(0, 1))).uppercase()
    }
}
