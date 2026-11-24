package app.foundwith.core

/** A call as the phone's call log records it. */
/** BLOCKED (by the phone or a spam app), VOICEMAIL and ELSEWHERE (answered on another device) since 24 Nov 2026:
 *  every kind of call counts (Y). OTHER: a kind this Android version doesn't name. */
enum class CallType { ANSWERED, MISSED, OUTGOING, REJECTED, BLOCKED, VOICEMAIL, ELSEWHERE, OTHER }

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
    /** When Android wrote the call to the call log, which is when it ended (Android 11+; null before). */
    val loggedAt: Long? = null,
) {
    /** When the call ended: when it was logged, or else its start plus how long it lasted. */
    val endedAt: Long get() = loggedAt ?: (at + duration * 1000)

    val e164: String? get() = PhoneNumbers.normalise(raw)
    /** A hidden ("Private") number: no number at all, or Android's -1/-2/-3 markers. */
    val isHidden: Boolean get() = raw.isNullOrBlank() || raw.trim().startsWith("-") || raw.none { it.isDigit() }
    /** Another country's number (checked like any other since 23 Nov 2026). */
    val isForeign: Boolean get() = !isHidden && e164?.startsWith("+91") == false
    val isIncoming: Boolean get() = type != CallType.OUTGOING
    val isDialled: Boolean get() = type == CallType.OUTGOING
    /** Every kind of call is told about (Y, 24 Nov 2026), blocked ones and voicemail included. */
    val isEligible: Boolean get() = true
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
    /** The total WhoHas last told about for this number, or null if it never has. A repeat call notifies only when it grew. */
    val lastNotified: Int? = null,
)

/** QUIET (Y, 24 Nov 2026): a call with nothing new, told in the notification panel only, no pop-up. */
enum class Alert { NONE, SILENT, SOUND, QUIET }

/**
 * [isNew]: something new or changed, so it pops up marked New. [nobody]: no one on WhoHas has the
 * number, so the quiet note offers Share in group.
 */
data class AfterCallNote(val alert: Alert, val title: String, val text: String, val number: String,
                         val isNew: Boolean = false, val nobody: Boolean = false) {
    companion object {
        val NONE = AfterCallNote(Alert.NONE, "", "", "")
    }
}

/**
 * When to tell the person something after a call, and what (updated 22 Nov 2026):
 *  - Calls: received, missed, rejected and dialled. A dialled call is told quietly, after it ends.
 *  - Only when someone on WhoHas has the number. Numbers nobody has stay silent (the call is in Call log).
 *  - Only when it is new or changed: the first time for a number, or when more people have it than
 *    when WhoHas last told. A repeat call from the same number with the same people is not told again.
 *  - Saved contacts: only when their list grew since the person last looked.
 *  - With sound: only a missed call from a number not in the contacts, by day. Everything else is silent.
 *  - From 9 pm to 9 am, nothing makes a sound.
 */
object AfterCallRules {
    const val QUIET_FROM_HOUR = 21
    const val QUIET_UNTIL_HOUR = 9

    fun isQuiet(hourOfDay: Int): Boolean = hourOfDay >= QUIET_FROM_HOUR || hourOfDay < QUIET_UNTIL_HOUR

    /** [repeats]: missed calls from this number in the last hour, this one included. */
    /** "Call from …" for a received call; the other kinds say what happened. */
    private fun otherTitle(type: CallType, who: String): String = when (type) {
        CallType.BLOCKED -> "Blocked call: $who"
        CallType.VOICEMAIL -> "Voicemail: $who"
        CallType.ELSEWHERE -> "Call from $who · answered on another device"
        CallType.OTHER -> "Call: $who"
        else -> "Call from $who"
    }

    /**
     * [everyCall] (Y, 24 Nov 2026, "Tell me after every call"): a call with nothing new is told too,
     * quietly ([Alert.QUIET]); what is new or changed pops up, marked New.
     */
    fun decide(call: CallEvent, info: CallerInfo, hourOfDay: Int, repeats: Int = 1, everyCall: Boolean = false): AfterCallNote {
        val news = news(call, info, hourOfDay, repeats)
        if (news.alert != Alert.NONE) return news.copy(isNew = true)
        if (!everyCall || call.isHidden || info.blocked) return news
        val e164 = call.e164 ?: return news
        val saved = !info.contactName.isNullOrBlank() && !Naming.looksLikeNumber(info.contactName)
        val who = if (saved) info.contactName!!.trim() else PhoneNumbers.display(e164)
        val missed = call.type == CallType.MISSED || call.type == CallType.REJECTED
        val title = when {
            call.isDialled -> "You called $who"
            missed && !saved && repeats >= 2 -> "$repeats missed calls: $who"
            missed -> "Missed call: $who"
            else -> otherTitle(call.type, who)
        }
        val text = when {
            info.total <= 0 -> if (saved) HolderText.NO_ONE_ELSE else HolderText.NO_ONE + " yet"
            info.knownNames.isNotEmpty() -> HolderText.knownTo(info.knownNames, info.total, info.lowerBound)
            else -> "${HolderText.count(info.total, info.lowerBound)} on WhoHas " +
                (if (info.total == 1 && !info.lowerBound) "has" else "have") + " this number"
        }
        return AfterCallNote(Alert.QUIET, title, text, e164, isNew = false, nobody = info.total <= 0)
    }

    private fun news(call: CallEvent, info: CallerInfo, hourOfDay: Int, repeats: Int): AfterCallNote {
        if (!call.isEligible || call.isHidden || info.blocked) return AfterCallNote.NONE
        val e164 = call.e164 ?: return AfterCallNote.NONE
        val dialled = call.isDialled
        val missed = call.type == CallType.MISSED || call.type == CallType.REJECTED
        val saved = !info.contactName.isNullOrBlank() && !Naming.looksLikeNumber(info.contactName)
        val line = HolderText.knownTo(info.knownNames, info.total, info.lowerBound)
        if (info.lastNotified != null && info.total <= info.lastNotified) return AfterCallNote.NONE

        if (saved) {
            val before = info.previousTotal ?: return AfterCallNote.NONE
            if (info.total <= before) return AfterCallNote.NONE
            val who = info.contactName!!.trim()
            val title = when {
                dialled -> "You called $who"
                missed -> "Missed call: $who"
                else -> otherTitle(call.type, who)
            }
            return AfterCallNote(Alert.SILENT, title, HolderText.changeFor(who, info.total, info.total - before), e164)
        }

        if (info.total <= 0) return AfterCallNote.NONE
        val name = Naming.of(null, info.ownerName, info.popularName, e164)
        val number = PhoneNumbers.display(e164)
        val title = when {
            dialled -> "You called $number"
            !missed -> otherTitle(call.type, number)
            repeats >= 2 -> "$repeats missed calls: $number"
            else -> "Missed call: $number"
        }
        // With no one known, say it plainly with "on WhoHas": the notification has no other context.
        val who = if (info.knownNames.isEmpty()) "${HolderText.count(info.total, info.lowerBound)} on WhoHas " +
            (if (info.total == 1 && !info.lowerBound) "has" else "have") + " this number" else line
        val text = when (name.source) {
            NameSource.THEIR_OWN -> "Their own name: ${name.text} · $who"
            NameSource.POPULAR -> "People know it as “${name.text}” · $who"
            else -> who
        }
        val alert = if (missed && !isQuiet(hourOfDay)) Alert.SOUND else Alert.SILENT
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
     * [calls]: recent call log rows, any order. [handled]: ids already dealt with. Received, missed, rejected and dialled
     * calls are considered. The newest call per number is notified; older ones from the same
     * number are folded into its "2 missed calls".
     */
    fun plan(calls: List<CallEvent>, handled: Set<Long>, now: Long): Plan {
        val fresh = ArrayList<CallEvent>()
        val late = ArrayList<CallEvent>()
        for (call in calls.sortedByDescending { it.at }) {
            if (call.id in handled || !call.isEligible || now - call.at > LOOK_BACK_MS) continue
            if (now - call.at <= NOTIFY_WITHIN_MS) fresh += call else late += call
        }
        val newestPerNumber = fresh.groupBy { it.e164 ?: "hidden:${it.id}" }.values.map { it.first() }
        return Plan(newestPerNumber.sortedBy { it.at }, late)
    }

    /** Missed calls from this number within the hour before [call], counting [call] itself. */
    fun repeats(call: CallEvent, calls: List<CallEvent>): Int {
        val e164 = call.e164 ?: return 1
        return calls.count {
            (it.type == CallType.MISSED || it.type == CallType.REJECTED) && it.e164 == e164 &&
                it.at <= call.at && call.at - it.at <= NOTIFY_WITHIN_MS
        }.coerceAtLeast(1)
    }
}

/** One phone's day for the brand health report: calls seen, and how many were checked in time. */
data class HealthDay(
    val day: String,
    val callsSeen: Int = 0,
    val callsChecked: Int = 0,
    /** Of the calls checked, how many within 10 seconds and within a minute of the call ending (23 Nov 2026). */
    val within10s: Int = 0,
    val within60s: Int = 0,
) {
    /** [secondsAfterEnd]: how long after the call ended it was checked, when known. */
    fun seen(checkedInTime: Boolean, secondsAfterEnd: Long? = null): HealthDay {
        val fast = if (checkedInTime) secondsAfterEnd else null
        return copy(
            callsSeen = callsSeen + 1,
            callsChecked = callsChecked + if (checkedInTime) 1 else 0,
            within10s = within10s + if (fast != null && fast <= 10) 1 else 0,
            within60s = within60s + if (fast != null && fast <= 60) 1 else 0,
        )
    }

    /**
     * Whether to offer the phone-settings fix: at least 2 calls in the last week that WhoHas
     * only got to after the hour was up, which means the phone is stopping it.
     */
    companion object {
        fun needsFix(lateCallsThisWeek: Int): Boolean = lateCallsThisWeek >= 2
    }
}
