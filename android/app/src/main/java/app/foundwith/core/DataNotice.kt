package app.foundwith.core

/**
 * The data notice on the Allow card, shown before Android asks for contacts and call log: Google
 * Play's "prominent disclosure" (User Data policy). It says what is sent, why, and what others see.
 */
object DataNotice {
    fun text(callLog: Boolean): String =
        if (callLog) {
            "WhoHas sends the numbers from your contacts and call log, and the names you saved, to its server, " +
                "to match them with other people on WhoHas. No call times, messages or recordings. " +
                "People who already have your number see your name when they check a number you have."
        } else {
            "WhoHas sends the numbers from your contacts, and the names you saved, to its server, " +
                "to match them with other people on WhoHas. " +
                "People who already have your number see your name when they check a number you have."
        }
}
