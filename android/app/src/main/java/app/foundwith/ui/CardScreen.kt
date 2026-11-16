package app.foundwith.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foundwith.AppGraph
import app.foundwith.core.AskOrder
import app.foundwith.core.Changes
import app.foundwith.core.HolderText
import app.foundwith.core.Messages
import app.foundwith.core.NameSource
import app.foundwith.core.PhoneNumbers
import app.foundwith.core.ShownName
import app.foundwith.data.ApiException
import app.foundwith.data.AppState
import app.foundwith.data.Card
import app.foundwith.data.Invites
import app.foundwith.data.LookupResult
import app.foundwith.data.OfflineException
import kotlinx.coroutines.launch

/**
 * One number's card, the same wherever it was opened from (Updates, Calls, Contacts, search, a
 * notification). What it shows follows the rules on the design's card screens 14 to 32:
 *  - Ask only on numbers you haven't saved, next to people you know who have it.
 *  - Invite only on saved mobile numbers whose owner hasn't joined.
 *  - Call back only after a call from a number you haven't saved.
 *  - ⋮: Share this number always; Save to contacts when it isn't saved; Block only for people on Found With.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScreen(graph: AppGraph, e164: String, onBack: () -> Unit, onNumber: (String) -> Unit, onInviteFriends: () -> Unit) {
    val state by graph.model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var looked by remember { mutableStateOf(graph.lookups[e164]) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showMeaning by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    val mine = state.isMine(e164)
    val card: Card? = if (mine) state.cards?.mine else state.cardFor(e164) ?: looked?.asCard()
    val onPhone = state.contactsByE164.containsKey(e164) || state.lastCall(e164) != null

    // A number with no card yet (nobody else has it, or it was never on this phone): ask once,
    // for its name and whether its owner is on Found With.
    LaunchedEffect(e164) {
        if (mine || state.cardFor(e164) != null || looked != null) return@LaunchedEffect
        try {
            val result: LookupResult = graph.api.lookup(e164, if (onPhone) "call" else "search")
            graph.lookups[e164] = result
            looked = result
        } catch (e: OfflineException) {
            problem = "No internet. This shows what Found With knew last."
        } catch (e: ApiException) {
            problem = if (e.code == "quota_exceeded") "Today's limit is over. Try again tomorrow." else e.message
        }
    }

    val saved = state.isSaved(e164)
    val lastCall = state.lastCall(e164)
    val blocked = state.isBlocked(card) || (looked?.ref?.let { it in state.blockedRefs } == true)
    val takenOut = looked?.takenOut == true
    val name = if (mine) ShownName("Your number", NameSource.CONTACT) else state.nameFor(e164, card)
    val total = card?.total ?: 0
    val now = System.currentTimeMillis()
    val change = card?.let { state.changes.changes[it.ref] }?.takeIf { Changes.isToday(it, now, startOfToday(now)) }
    val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            if (!mine && !takenOut) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Share this number") }, onClick = {
                            menu = false
                            context.shareText(Messages.share(name.text, e164, total, link, card?.totalIsLowerBound == true))
                        })
                        if (!saved) DropdownMenuItem(text = { Text("Save to contacts") }, onClick = { menu = false; context.saveContact(e164) })
                        if (card?.onFoundWith == true && card.ownerId != null && !blocked) {
                            DropdownMenuItem(text = { Text("Block ${name.text}", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmBlock = true })
                        }
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NameText(name, big = true, maxLines = 2)
            val sub = buildList {
                add(shown(e164))
                if (!saved && !mine && lastCall != null && lastCall.isIncoming) add(callLine(lastCall))
            }.joinToString(" · ")
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when {
                takenOut -> {
                    Spacer(Modifier.height(8.dp))
                    Text("Not shown on Found With", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("The owner took this number out.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                blocked -> {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(50)) {
                        Text("Blocked", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                    Text("You and ${name.text} don't see each other on Found With.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryButton("Unblock", {
                        val ref = card?.ref ?: looked?.ref
                        if (ref != null) scope.launch { problem = graph.model.unblock(ref) }
                    })
                }
                else -> {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.clickable { showMeaning = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text(state.lineFor(card, onYourPhone = saved || mine), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Info, contentDescription = "What it means", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(18.dp))
                    }
                    val labelLine = if (mine) listOfNotNull(HolderText.label(total), HolderText.nextLabel(total)).joinToString(" · ")
                    else HolderText.label(total, card?.totalIsLowerBound == true)
                    if (!labelLine.isNullOrEmpty()) Text(labelLine, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (change != null) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(10.dp)) {
                            Text(if (mine) "Today: ${HolderText.more(change.delta)}" else HolderText.today(change.delta, change.before, change.now),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            ErrorText(problem)
        }

        if (!takenOut && !blocked && card != null && card.known.isNotEmpty()) {
            val people = AskOrder.order(state.knownPeople(card))
            val askMode = !saved && !mine
            Spacer(Modifier.height(8.dp))
            SectionLabel(if (askMode) "Ask someone you know" else "People you know who have it")
            val shownPeople = if (showAll) people else people.take(3)
            for ((person, why) in shownPeople) {
                NumberRow(
                    leading = { Avatar(person.id, person.name, 36.dp) },
                    name = ShownName(person.name, NameSource.CONTACT),
                    line = if (askMode) why.hint else (if (person.inContacts) "Contact" else "In your call log"),
                    trailing = if (askMode) ({
                        OutlinedButton(onClick = { context.messagePerson(person.e164, Messages.ask(e164, card.name, card.popularName)) }) { Text("Ask") }
                    }) else null,
                    onClick = person.e164?.let { p -> { onNumber(p) } },
                )
            }
            if (!showAll && people.size > 3) {
                TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("See all ${people.size}") }
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val member = card?.onFoundWith == true || state.refFor(e164)?.let { it in state.cards?.circle?.memberRefs.orEmpty() } == true
            if (mine) {
                PrimaryButton("Invite friends", onInviteFriends)
            } else if (!takenOut && !blocked) {
                if (saved && PhoneNumbers.isIndianMobile(e164) && !member) {
                    Text("${name.text} isn't on Found With yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    PrimaryButton("Invite", { context.messagePerson(e164, Messages.invite(total, link, card?.totalIsLowerBound == true)) })
                }
                if (!saved && lastCall != null && lastCall.isIncoming) {
                    OutlinedButton(onClick = { context.dial(e164) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Filled.Call, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Call back")
                    }
                }
            }
        }
    }

    if (showMeaning) {
        ModalBottomSheet(onDismissRequest = { showMeaning = false }) {
            Meaning(state, card, name, saved || mine, onDone = { showMeaning = false })
        }
    }
    if (confirmBlock) {
        ConfirmDialog(
            title = "Block ${name.text}?",
            text = "You and ${name.text} won't see each other on Found With. Calls and messages aren't affected.",
            confirm = "Block",
            danger = true,
            onConfirm = {
                confirmBlock = false
                card?.ownerId?.let { id -> scope.launch { problem = graph.model.block(id) } }
            },
            onDismiss = { confirmBlock = false },
        )
    }
}

/** "What it means": the line explained in the order it's read. Only the rows that apply. */
@Composable
private fun Meaning(state: AppState, card: Card?, name: ShownName, onYourPhone: Boolean, onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.lineFor(card, onYourPhone), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val total = card?.total ?: 0
        if (total == 0) {
            Text(if (onYourPhone) "No one else on Found With has this number yet." else "No one on Found With has this number yet.")
        }
        if (card != null && card.known.isNotEmpty()) {
            val people = card.known.map { state.personName(it) }
            val names = if (people.size <= 2) people.joinToString(" and ") else people.take(2).joinToString(", ") + " and ${people.size - 2} more"
            MeaningRow(names, if (people.size == 1) "Has this number. You know them: they're on your phone." else "Have this number. You know them: they're on your phone.")
        }
        val rest = total - (card?.known?.size ?: 0)
        if (rest > 0) {
            MeaningRow(HolderText.others(rest, card?.totalIsLowerBound == true).let { if (card?.known.isNullOrEmpty()) HolderText.count(rest) else it },
                "Also have it. They aren't on your phone, so you see only how many.")
        }
        HolderText.labelMeaning(total)?.let { MeaningRow(HolderText.label(total)!!, it) }
        when (name.source) {
            NameSource.POPULAR -> MeaningRow(name.text, "The name at least 3 people saved for this number.")
            NameSource.THEIR_OWN -> MeaningRow(name.text, "The name this person gave on Found With. Anyone can type any name.")
            else -> Unit
        }
        PrimaryButton("Got it", onDone)
    }
}

@Composable
private fun MeaningRow(title: String, text: String) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
