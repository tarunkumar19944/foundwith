package app.foundwith.core

/** Someone shown on a card or in a notification, with the name the viewer should see. */
data class ShownPerson(val name: String, val known: Boolean)

/**
 * The one line every card, search result, list row and notification shows, so Found With reads
 * the same everywhere: "Found with Ravi Kumar, Priya Sharma and 2 others". Counts never include
 * the viewer.
 */
object HolderText {
    const val NO_ONE = "Found with no one yet"
    /** For a number on the viewer's own phone that nobody else has: "no one" would read as wrong. */
    const val ONLY_YOU = "Only you have this number"
    const val POPULAR_FROM = 10
    const val HIGHLY_POPULAR_FROM = 50

    /**
     * "Found with Ravi Kumar", "Found with Ravi Kumar and Priya Sharma",
     * "Found with Ravi, Priya, Meena and 120 others", "Found with 12 people" when there are no
     * names to show. With nobody: [ONLY_YOU] when the number is on the viewer's phone, else [NO_ONE].
     */
    fun foundWith(
        people: List<ShownPerson>,
        total: Int,
        lowerBound: Boolean = false,
        max: Int = 3,
        onYourPhone: Boolean = false,
    ): String {
        if (total <= 0) return if (onYourPhone) ONLY_YOU else NO_ONE
        val shown = people.take(max).map { it.name }
        if (shown.isEmpty()) return "Found with " + count(total, lowerBound)
        val rest = total - shown.size
        if (rest <= 0) {
            return "Found with " + if (shown.size == 1) shown[0] else shown.dropLast(1).joinToString(", ") + " and " + shown.last()
        }
        return "Found with " + shown.joinToString(", ") + " and " + others(rest, lowerBound)
    }

    /** "Popular number" (10 to 49 people) or "Highly popular number" (50 and up). None under 10. */
    fun label(total: Int, lowerBound: Boolean = false): String? = when {
        total >= HIGHLY_POPULAR_FROM || (lowerBound && total >= HIGHLY_POPULAR_FROM) -> "Highly popular number"
        total >= POPULAR_FROM -> "Popular number"
        else -> null
    }

    /** For the person's own number: "6 more people to a Popular number", "19 more people to Highly popular". */
    fun nextLabel(total: Int): String? = when {
        total >= HIGHLY_POPULAR_FROM -> null
        total >= POPULAR_FROM -> "${more(HIGHLY_POPULAR_FROM - total)} to Highly popular"
        else -> "${more(POPULAR_FROM - total)} to a Popular number"
    }

    /** "1 more person", "3 more people". */
    fun more(n: Int): String = if (n == 1) "1 more person" else "${grouped(n)} more people"

    /** An Updates row and the after-call notification: "Now found with 12 · 3 more". */
    fun change(total: Int, delta: Int): String = "Now found with ${grouped(total)} · ${grouped(delta)} more"

    /** The green line on a card that changed today: "Today: 3 more people · now a Popular number". */
    fun today(delta: Int, before: Int, now: Int): String {
        val becameLabel = label(now) != null && label(now) != label(before)
        val became = when {
            !becameLabel -> ""
            now >= HIGHLY_POPULAR_FROM -> " · now a Highly popular number"
            else -> " · now a Popular number"
        }
        return "Today: ${more(delta)}$became"
    }

    /** What the "What it means" sheet says about the label. */
    fun labelMeaning(total: Int): String? = when (label(total)) {
        "Highly popular number" -> "${count(total)} have it. 50 or more is a highly popular number."
        "Popular number" -> "${count(total)} have it. 10 to 49 is a popular number."
        else -> null
    }

    /** "1 person", "12 people", "10,000+ people". */
    fun count(total: Int, lowerBound: Boolean = false): String =
        grouped(total) + (if (lowerBound) "+" else "") + if (total == 1 && !lowerBound) " person" else " people"

    /** "1 other", "120 others", "9,997+ others". */
    fun others(rest: Int, lowerBound: Boolean = false): String =
        grouped(rest) + (if (lowerBound) "+" else "") + if (rest == 1 && !lowerBound) " other" else " others"

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
