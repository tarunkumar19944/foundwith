package app.foundwith.core

/**
 * The words for deleting an account (docs/WHOHAS_COPY.md, Play's account deletion rule). The
 * contacts-only version never reads the call log, so it never mentions it.
 */
object AccountText {
    fun deleteList(callLog: Boolean): List<String> = listOf(
        "Your number and name",
        if (callLog) "Your contacts and call log numbers" else "Your contact numbers",
        "Your blocked list and settings",
    )

    fun deleteFoot(callLog: Boolean): String =
        "Other people’s counts go down by one. Your phone’s own " +
            (if (callLog) "contacts and call log are" else "contacts are") + " not touched. You can’t undo this."

    /** The whole dialog text: what goes, as a list, then what doesn't change. */
    fun deleteDialog(callLog: Boolean): String =
        "This removes, from WhoHas:\n" + deleteList(callLog).joinToString("\n") { "• $it" } + "\n\n" + deleteFoot(callLog)

    fun deleted(callLog: Boolean): String =
        if (callLog) "Your number, name, contacts and call log numbers are gone from WhoHas."
        else "Your number, name and contact numbers are gone from WhoHas."
}
