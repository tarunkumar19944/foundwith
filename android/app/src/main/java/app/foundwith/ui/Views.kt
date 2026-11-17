package app.foundwith.ui

import app.foundwith.core.AskOrder
import app.foundwith.core.CallEvent
import app.foundwith.core.CallType
import app.foundwith.core.HolderText
import app.foundwith.core.KnownPerson
import app.foundwith.core.Naming
import app.foundwith.core.PhoneNumbers
import app.foundwith.core.ShownName
import app.foundwith.data.AppState
import app.foundwith.data.Card
import app.foundwith.data.Holder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How the screens read the app's state: one place for "what is this number called" and "who has it". */

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

fun AppState.refFor(e164: String): String? = index.refByE164[e164]

fun AppState.cardFor(e164: String): Card? =
    refFor(e164)?.let { cardsByRef[it] } ?: cards?.mine?.takeIf { isMine(e164) }

fun AppState.isMine(e164: String): Boolean = profile?.phone == e164

fun AppState.contactName(e164: String): String? = contactsByE164[e164]?.shownName

fun AppState.isSaved(e164: String): Boolean = contactName(e164)?.let { !Naming.looksLikeNumber(it) } == true

/** Rule 4: your name for it, their own name, what others call it, the number. */
fun AppState.nameFor(e164: String, card: Card?): ShownName =
    Naming.of(contactName(e164), card?.name, card?.popularName, e164)

/** How someone who has the number is shown: your name for them, else their own, else their number. */
fun AppState.personName(holder: Holder): String {
    val label = holder.contactRef?.let { index.byRef[it] }
    return Naming.person(label?.name, holder.name, label?.e164)
}

fun AppState.knownNames(card: Card): List<String> = card.known.map { personName(it) }

/**
 * Names of people the viewer knows who have the number, in Ask order (contacts first, then call
 * log), for one-line summaries. Someone with no name to show (only in the call log, no WhoHas
 * name) is left out here: a bare number in "You know …" means nothing. They still count, and
 * they are listed on the card.
 */
fun AppState.knownOrdered(card: Card): List<String> =
    AskOrder.order(knownPeople(card)).map { it.first.name }.filterNot { Naming.looksLikeNumber(it) }

/** A contact or search row: "5 people have it · You know Priya and Meena". */
fun AppState.lineFor(card: Card?, onYourPhone: Boolean): String {
    if (card == null) return HolderText.haveItLine(emptyList(), 0, 0, onYourPhone = onYourPhone)
    return HolderText.haveItLine(knownOrdered(card), card.knownTotal, card.total, card.totalIsLowerBound, onYourPhone)
}

/** A call row, about the caller: "Known to Priya, Meena and 10 more". */
fun AppState.callerLine(card: Card?, onYourPhone: Boolean): String {
    if (card == null) return HolderText.knownTo(emptyList(), 0, onYourPhone = onYourPhone)
    return HolderText.knownTo(knownOrdered(card), card.total, card.totalIsLowerBound, onYourPhone)
}

/** Whether a contact's number belongs to someone on WhoHas. */
fun AppState.onWhoHas(e164: String, card: Card?): Boolean =
    card?.onFoundWith == true || refFor(e164)?.let { it in cards?.circle?.memberRefs.orEmpty() } == true

/** The people you know who have the number, for the Ask list (ordered by [app.foundwith.core.AskOrder]). */
fun AppState.knownPeople(card: Card): List<KnownPerson> = card.known.map { holder ->
    val label = holder.contactRef?.let { index.byRef[it] }
    val e164 = label?.e164
    val contact = e164?.let { contactsByE164[it] }
    KnownPerson(
        id = holder.id,
        name = personName(holder),
        e164 = e164,
        inContacts = contact != null,
        starred = contact?.starred == true,
        calls = e164?.let { callCounts[it] } ?: 0,
    )
}

fun AppState.lastCall(e164: String): CallEvent? = calls.firstOrNull { it.e164 == e164 }

fun AppState.isBlocked(card: Card?): Boolean = card != null && card.ref in blockedRefs

/** What kind of call it was, in the words of the phone's own call log. */
fun callKind(call: CallEvent): String = when (call.type) {
    CallType.MISSED -> "Missed call"
    CallType.REJECTED -> "Rejected"
    CallType.ANSWERED -> "Received"
    CallType.OUTGOING -> if (call.duration > 0) "You called" else "You called · not answered"
    CallType.OTHER -> "Call"
}

/** "4 min", "45 sec", "1 hr 5 min". */
fun durationText(seconds: Long): String = when {
    seconds < 60 -> "$seconds sec"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} hr" + (seconds % 3600 / 60).let { if (it > 0) " $it min" else "" }
}

/** "today, 10:42 am", "yesterday, 6:15 pm", "Fri, 5:40 pm", "12 Nov, 5:40 pm". */
fun callWhen(at: Long, now: Long = System.currentTimeMillis()): String {
    val time = Instant.ofEpochMilli(at).atZone(IST)
    val today = Instant.ofEpochMilli(now).atZone(IST).toLocalDate()
    val day = time.toLocalDate()
    val clock = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase(Locale.ENGLISH)
    val date = when {
        day == today -> "today"
        day == today.minusDays(1) -> "yesterday"
        day.isAfter(today.minusDays(7)) -> time.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))
        else -> time.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
    return "$date, $clock"
}

/**
 * "Missed call · today, 10:42 am", "Received · yesterday, 6:15 pm · 4 min". With [withDay] false
 * (under a day heading in the call log) just the time: "Received · 6:15 pm · 4 min".
 */
fun callLine(call: CallEvent, now: Long = System.currentTimeMillis(), withDay: Boolean = true): String {
    val at = if (withDay) callWhen(call.at, now)
    else Instant.ofEpochMilli(call.at).atZone(IST).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase(Locale.ENGLISH)
    val talked = call.duration.takeIf { it > 0 && (call.type == CallType.ANSWERED || call.type == CallType.OUTGOING) }
    return "${callKind(call)} · $at" + (talked?.let { " · ${durationText(it)}" } ?: "")
}

/** The heading over a day of calls: "Today", "Yesterday", "Fri", "12 Nov". */
fun dayHeading(at: Long, now: Long = System.currentTimeMillis()): String {
    val day = Instant.ofEpochMilli(at).atZone(IST).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(IST).toLocalDate()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
        else -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
}

/** Every call with this number, newest first. */
fun AppState.callsWith(e164: String): List<CallEvent> = calls.filter { it.e164 == e164 }

const val ONLY_INDIAN = "WhoHas checks only Indian numbers"

/** "10:42 am" today, "Yesterday", "Fri" this week, "3 Nov" before that. */
fun whenText(at: Long, now: Long = System.currentTimeMillis()): String {
    val time = Instant.ofEpochMilli(at).atZone(IST)
    val today = Instant.ofEpochMilli(now).atZone(IST).toLocalDate()
    val day = time.toLocalDate()
    return when {
        day == today -> time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase(Locale.ENGLISH)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> time.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))
        else -> time.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
}

/** "today", "yesterday", "on Fri", "on 12 Nov": for "called you …". */
fun dayText(at: Long, now: Long = System.currentTimeMillis()): String {
    val day = Instant.ofEpochMilli(at).atZone(IST).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(IST).toLocalDate()
    return when {
        day == today -> "today"
        day == today.minusDays(1) -> "yesterday"
        day.isAfter(today.minusDays(7)) -> "on " + day.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))
        else -> "on " + day.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
}

fun startOfToday(now: Long = System.currentTimeMillis()): Long =
    Instant.ofEpochMilli(now).atZone(IST).toLocalDate().atStartOfDay(IST).toInstant().toEpochMilli()

/** The number as people read it. */
fun shown(e164: String): String = PhoneNumbers.display(e164)
