package app.foundwith.core

/**
 * How many people have a number, as a level (designed 22 Nov 2026). Your own number's level is the
 * hook to share WhoHas in a group; on other numbers it is a label. The levels use the same counts
 * as the "Many people have this number" label: 10 and 50. Counts are always real.
 */
enum class Level(val label: String, val why: String) {
    NOT_POPULAR("Not popular", "Fewer than 10 people have it"),
    POPULAR("Popular", "10 to 49 people have it. Often a business, or someone who calls a lot of people"),
    HIGHLY_POPULAR("Highly popular", "50 or more people have it. Often a business, or someone who calls a lot of people"),
}

/** This number's level beside yours. */
enum class Standing { AHEAD, SAME, BEHIND }

data class Comparison(val standing: Standing, val text: String)

object Levels {
    const val POPULAR_FROM = 10
    const val HIGHLY_POPULAR_FROM = 50

    fun of(total: Int, lowerBound: Boolean = false): Level = when {
        total >= HIGHLY_POPULAR_FROM || (lowerBound && total >= POPULAR_FROM) -> Level.HIGHLY_POPULAR
        total >= POPULAR_FROM -> Level.POPULAR
        else -> Level.NOT_POPULAR
    }

    /** On a list row only Popular and Highly popular show a tag; "Not popular" stays on cards and on your own number. */
    fun rowTag(total: Int, lowerBound: Boolean = false): Level? = of(total, lowerBound).takeIf { it != Level.NOT_POPULAR }

    /** The count that reaches the next level, or null at the top. */
    fun target(level: Level): Int? = when (level) {
        Level.NOT_POPULAR -> POPULAR_FROM
        Level.POPULAR -> HIGHLY_POPULAR_FROM
        Level.HIGHLY_POPULAR -> null
    }

    fun next(level: Level): Level? = when (level) {
        Level.NOT_POPULAR -> Level.POPULAR
        Level.POPULAR -> Level.HIGHLY_POPULAR
        Level.HIGHLY_POPULAR -> null
    }

    /** How many more people reach the next level; null at the top. */
    fun left(total: Int): Int? = target(of(total))?.let { it - total }

    /** How far the next level is (23 Nov 2026): people left, the next level, and how much of the way is done (0 to 1). */
    data class Step(val left: Int, val next: Level, val done: Float)

    /** Null at the top level. Counts are real; [done] only draws the line under your number. */
    fun step(total: Int): Step? {
        val level = of(total)
        val next = next(level) ?: return null
        val to = target(level) ?: return null
        val from = if (level == Level.NOT_POPULAR) 0 else POPULAR_FROM
        val done = ((total.coerceAtLeast(0) - from).toFloat() / (to - from)).coerceIn(0f, 1f)
        return Step(to - total.coerceAtLeast(0), next, done)
    }

    /** Beside the line in the number bar: "2 more → Popular"; null at the top. */
    fun barLine(total: Int): String? = step(total)?.let { "${HolderText.grouped(it.left)} more → ${it.next.label}" }

    /** On your own card, what the next level's tag does: "At 10 people, the Popular tag shows beside your number for everyone who has it." */
    fun tagNote(total: Int): String? = step(total)?.let { s ->
        "At ${HolderText.grouped(target(of(total))!!)} people, the ${s.next.label} tag shows beside your number for everyone who has it."
    }

    /** "10 more people and you’re Popular", "1 more person and you’re Popular"; null at the top. */
    fun progress(total: Int): String? {
        val left = left(total) ?: return null
        val next = next(of(total)) ?: return null
        return "${HolderText.more(left)} and you’re ${next.label}"
    }

    /** The short line beside your own tag (docs/WHOHAS_COPY.md). Empty at the top. */
    fun why(level: Level): String = when (level) {
        Level.NOT_POPULAR -> "Share WhoHas so people you know can find you."
        Level.POPULAR -> "More people means more callers you can recognise."
        Level.HIGHLY_POPULAR -> ""
    }

    /** Under the bar, with the Share in group link: what sharing in a group does. */
    const val POOL_LINE = "Share in a group so everyone pools their numbers. Then you can find who has any number that calls you."

    /** At the top level, in place of the bar: "Top level. 52 people have your number". */
    fun topLine(total: Int): String = "Top level. " + HolderText.haveYours(total)

    /** The day you reach a level: "10 people have your number. 40 more and you’re Highly popular." */
    fun reachedText(total: Int): String {
        val left = left(total)
        val next = next(of(total))
        return HolderText.haveYours(total) + "." + if (left != null && next != null) " ${HolderText.grouped(left)} more and you’re ${next.label}." else " That is the top level."
    }

    /** Beside your own level on a card: where this number stands. Levels only, from real counts. */
    fun compare(theirs: Level, mineTotal: Int): Comparison {
        val mine = of(mineTotal)
        return when {
            theirs.ordinal > mine.ordinal -> {
                val left = target(mine)!! - mineTotal
                Comparison(Standing.AHEAD, "More popular than your number. ${HolderText.more(left)} and you catch up.")
            }
            theirs == mine -> Comparison(Standing.SAME, "Same level as your number.")
            else -> Comparison(Standing.BEHIND, "Your number is ahead of this one.")
        }
    }
}

/** A notification about your own level. */
data class LevelNote(val title: String, val text: String)

/**
 * Level notifications, silent, real counts only (22 Nov 2026):
 *  - Reaching a new level: "You’re Popular now".
 *  - Within [NUDGE_WITHIN] people of the next level: one nudge, at most once a week.
 * [announced] is the highest level already told (or set as the starting point on the first refresh).
 */
object LevelRules {
    const val NUDGE_WITHIN = 2
    const val NUDGE_EVERY_MS = 7 * 24 * 60 * 60 * 1000L

    data class Decision(val note: LevelNote?, val announced: Int, val nudged: Boolean = false)

    /**
     * [announced] never goes down: a count that dips below a level and comes back is not told
     * again, so a number at 9 and 10 by turns doesn't notify every day.
     */
    fun decide(announced: Int, total: Int, lastNudgeAt: Long, now: Long): Decision {
        val level = Levels.of(total)
        if (level.ordinal > announced) {
            return Decision(LevelNote("You’re ${level.label} now", Levels.reachedText(total)), level.ordinal)
        }
        val left = Levels.left(total)
        if (left != null && left <= NUDGE_WITHIN && now - lastNudgeAt >= NUDGE_EVERY_MS) {
            val title = Levels.progress(total)!!.replaceFirstChar { it.uppercase() }
            return Decision(LevelNote(title, HolderText.haveYours(total) + ". Share WhoHas in a group."), announced, nudged = true)
        }
        return Decision(null, announced)
    }
}
