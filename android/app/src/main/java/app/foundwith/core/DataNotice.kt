package app.foundwith.core

/**
 * The data notice on the Allow card, shown before Android asks for contacts and call log: Google
 * Play's "prominent disclosure" (User Data policy). It says what is sent, why, and what others see.
 */
object DataNotice {
    fun text(callLog: Boolean): String =
        if (callLog) {
            "WhoHas sends your contact and call log numbers, and the names you saved, to its server to match them. " +
                "People who have your number see your name."
        } else {
            "WhoHas sends your contact numbers, and the names you saved, to its server to match them. " +
                "People who have your number see your name."
        }
}
