package app.foundwith.ui

import app.foundwith.core.CallEvent
import app.foundwith.core.CallType
import app.foundwith.core.HolderText
import app.foundwith.core.KnownPerson
import app.foundwith.core.Naming
import app.foundwith.core.PhoneNumbers
import app.foundwith.core.ShownName
import app.foundwith.core.ShownPerson
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

/** Rule 4: your name for it, their own name, the popular name, the number. */
fun AppState.nameFor(e164: String, card: Card?): ShownName =
    Naming.of(contactName(e164), card?.name, card?.popularName, e164)

/** How someone who has the number is shown: your name for them, else their own, else their number. */
fun AppState.personName(holder: Holder): String {
    val label = holder.contactRef?.let { index.byRef[it] }
    return Naming.person(label?.name, holder.name, label?.e164)
}

fun AppState.knownNames(card: Card): List<String> = card.known.map { personName(it) }

/** The one line: "Found with Priya, Ravi and 3 others". */
fun AppState.lineFor(card: Card?, onYourPhone: Boolean): String {
    if (card == null) return HolderText.foundWith(emptyList(), 0, onYourPhone = onYourPhone)
    val people = card.known.map { ShownPerson(personName(it), true) } + card.others.map { ShownPerson(personName(it), false) }
    return HolderText.foundWith(people, card.total, card.totalIsLowerBound, onYourPhone = onYourPhone)
}

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

/** "Missed call · 10:42 am", "Answered call · Yesterday", "Call you made · Fri". */
fun callLine(call: CallEvent, now: Long = System.currentTimeMillis()): String {
    val kind = when (call.type) {
        CallType.MISSED, CallType.REJECTED -> "Missed call"
        CallType.ANSWERED -> "Answered call"
        CallType.OUTGOING -> "Call you made"
        CallType.OTHER -> "Call"
    }
    return "$kind · ${whenText(call.at, now)}"
}

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

fun startOfToday(now: Long = System.currentTimeMillis()): Long =
    Instant.ofEpochMilli(now).atZone(IST).toLocalDate().atStartOfDay(IST).toInstant().toEpochMilli()

/** The number as people read it. */
fun shown(e164: String): String = PhoneNumbers.display(e164)
