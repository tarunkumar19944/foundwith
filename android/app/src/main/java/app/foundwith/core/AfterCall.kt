package app.foundwith.core

/** A call as the phone's call log records it. */
enum class CallType { ANSWERED, MISSED, OUTGOING, REJECTED, OTHER }

data class CallEvent(
    /** The call log's row id: never reused, so a call is only ever handled once. */
    val id: Long,
    /** The number as the call log has it. Empty or null for a hidden ("Private") number. */
    val raw: String?,
    val type: CallType,
    /** When the call started, in milliseconds. */
    val at: Long,
    /** The phone's cached contact name for this call, if any. */
    val cachedName: String? = null,
    /** How long the call lasted, in seconds (0 for a missed call or one nobody answered). */
    val duration: Long = 0,
) {
    val e164: String? get() = PhoneNumbers.normalise(raw)
    /** A hidden ("Private") number: no number at all, or Android's -1/-2/-3 markers. */
    val isHidden: Boolean get() = raw.isNullOrBlank() || raw.trim().startsWith("-") || raw.none { it.isDigit() }
    /** Another country's number: WhoHas checks Indian numbers only. */
    val isForeign: Boolean get() = !isHidden && e164 == null && PhoneNumbers.isForeign(raw)
    val isIncoming: Boolean get() = type == CallType.ANSWERED || type == CallType.MISSED || type == CallType.REJECTED
}

/** What WhoHas knows about the caller's number when deciding. */
data class CallerInfo(
    /** The viewer's own contact name, when the number is saved. */
    val contactName: String?,
    val total: Int,
    val lowerBound: Boolean = false,
    /** How people the viewer knows are shown, in order. */
    val knownNames: List<String> = emptyList(),
    val ownerName: String? = null,
    val popularName: String? = null,
    /** The total the viewer last saw for this number (saved contacts only), for "3 more". */
    val previousTotal: Int? = null,
    val blocked: Boolean = false,
)

enum class Alert { NONE, SILENT, SOUND }

data class AfterCallNote(val alert: Alert, val title: String, val text: String, val number: String) {
    companion object {
        val NONE = AfterCallNote(Alert.NONE, "", "", "")
    }
}

/**
 * When to tell the person something after a call, and what (decided 15 Nov 2026):
 *  - With sound: only a missed call from a number they haven't saved, when someone has that number
 *    or it has a name. Most unsaved calls in India are deliveries, cabs and sales calls; a sound for
 *    every one of them would get the app uninstalled.
 *  - Silent: answered calls from unsaved numbers that someone has, and calls from saved contacts
 *    whose list grew since they last looked.
 *  - Nothing: calls they made, hidden numbers, blocked people, saved contacts with no change, and
 *    unsaved numbers nobody has and that have no name (the call is still in the Calls tab).
 *  - From 9 pm to 9 am, nothing makes a sound.
 */
object AfterCallRules {
    const val QUIET_FROM_HOUR = 21
    const val QUIET_UNTIL_HOUR = 9

    fun isQuiet(hourOfDay: Int): Boolean = hourOfDay >= QUIET_FROM_HOUR || hourOfDay < QUIET_UNTIL_HOUR

    /** [repeats]: missed calls from this number in the last hour, this one included. */
    fun decide(call: CallEvent, info: CallerInfo, hourOfDay: Int, repeats: Int = 1): AfterCallNote {
        if (!call.isIncoming || call.isHidden || info.blocked) return AfterCallNote.NONE
        val e164 = call.e164 ?: return AfterCallNote.NONE
        val missed = call.type != CallType.ANSWERED
        val saved = !info.contactName.isNullOrBlank() && !Naming.looksLikeNumber(info.contactName)
        val line = HolderText.knownTo(info.knownNames, info.total, info.lowerBound)

        if (saved) {
            val before = info.previousTotal ?: return AfterCallNote.NONE
            if (info.total <= before) return AfterCallNote.NONE
            val title = (if (missed) "Missed call: " else "Call from ") + info.contactName!!.trim()
            return AfterCallNote(Alert.SILENT, title, HolderText.changeFor(info.contactName.trim(), info.total, info.total - before), e164)
        }

        val name = Naming.of(null, info.ownerName, info.popularName, e164)
        if (info.total <= 0 && name.isNumber) return AfterCallNote.NONE
        val number = PhoneNumbers.display(e164)
        val title = when {
            !missed -> "Call from $number"
            repeats >= 2 -> "$repeats missed calls: $number"
            else -> "Missed call: $number"
        }
        // With no one known, say it plainly with "on WhoHas": the notification has no other context.
        val who = if (info.knownNames.isEmpty() && info.total > 0) "${HolderText.count(info.total, info.lowerBound)} on WhoHas " +
            (if (info.total == 1 && !info.lowerBound) "has" else "have") + " this number" else line
        val text = when (name.source) {
            NameSource.THEIR_OWN -> "Their own name: ${name.text} · $who"
            NameSource.POPULAR -> "People know it as “${name.text}” · $who"
            else -> who
        }
        val alert = when {
            !missed -> Alert.SILENT
            isQuiet(hourOfDay) -> Alert.SILENT
            else -> Alert.SOUND
        }
        return AfterCallNote(alert, title, text, e164)
    }
}

/**
 * Which calls still need checking. WhoHas is woken when a call lands in the call log, again
 * when the app opens, and by the server a few times a day; whichever comes first handles a call.
 * A call is only notified about while it is still fresh: an hour later, a notification is noise,
 * and the call is in the Calls tab anyway.
 */
object CallCatchUp {
    const val NOTIFY_WITHIN_MS = 60 * 60 * 1000L
    const val LOOK_BACK_MS = 7 * 24 * 60 * 60 * 1000L

    data class Plan(val toNotify: List<CallEvent>, val tooLate: List<CallEvent>)

    /**
     * [calls]: recent call log rows, any order. [handled]: ids already dealt with. Only incoming
     * calls are considered. The newest call per number is notified; older ones from the same
     * number are folded into its "2 missed calls".
     */
    fun plan(calls: List<CallEvent>, handled: Set<Long>, now: Long): Plan {
        val fresh = ArrayList<CallEvent>()
        val late = ArrayList<CallEvent>()
        for (call in calls.sortedByDescending { it.at }) {
            if (call.id in handled || !call.isIncoming || now - call.at > LOOK_BACK_MS) continue
            if (now - call.at <= NOTIFY_WITHIN_MS) fresh += call else late += call
        }
        val newestPerNumber = fresh.groupBy { it.e164 ?: "hidden:${it.id}" }.values.map { it.first() }
        return Plan(newestPerNumber.sortedBy { it.at }, late)
    }

    /** Missed calls from this number within the hour before [call], counting [call] itself. */
    fun repeats(call: CallEvent, calls: List<CallEvent>): Int {
        val e164 = call.e164 ?: return 1
        return calls.count {
            it.type != CallType.ANSWERED && it.isIncoming && it.e164 == e164 &&
                it.at <= call.at && call.at - it.at <= NOTIFY_WITHIN_MS
        }.coerceAtLeast(1)
    }
}

/** One phone's day for the brand health report: calls seen, and how many were checked in time. */
data class HealthDay(val day: String, val callsSeen: Int = 0, val callsChecked: Int = 0) {
    fun seen(checkedInTime: Boolean) = copy(callsSeen = callsSeen + 1, callsChecked = callsChecked + if (checkedInTime) 1 else 0)

    /**
     * Whether to offer the phone-settings fix: at least 2 calls in the last week that WhoHas
     * only got to after the hour was up, which means the phone is stopping it.
     */
    companion object {
        fun needsFix(lateCallsThisWeek: Int): Boolean = lateCallsThisWeek >= 2
    }
}
