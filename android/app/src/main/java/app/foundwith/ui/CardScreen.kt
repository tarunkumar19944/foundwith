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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foundwith.AppGraph
import app.foundwith.core.AskOrder
import app.foundwith.core.CallType
import app.foundwith.core.Naming
import app.foundwith.core.Changes
import app.foundwith.core.HolderText
import app.foundwith.core.Level
import app.foundwith.core.Levels
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
 * notification). Order (docs/WHOHAS_COPY.md): who it is → how many have it → people you know →
 * one main action → the list → "What does this mean?".
 *  - Ask only on numbers you haven't saved; it picks someone in your contacts first.
 *  - Invite only on saved mobile numbers whose owner isn't on WhoHas.
 *  - Call back only after a call from a number you haven't saved.
 *  - ⋮: Share number always; Save to contacts when it isn't saved; Block only for people on WhoHas.
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
    var showAllCalls by remember { mutableStateOf(false) }
    var invited by remember { mutableStateOf(false) }

    val mine = state.isMine(e164)
    val card: Card? = if (mine) state.cards?.mine else state.cardFor(e164) ?: looked?.asCard()
    val onPhone = state.contactsByE164.containsKey(e164) || state.lastCall(e164) != null

    // A number with no card yet (nobody else has it, or it was never on this phone): ask once,
    // for its name and whether its owner is on WhoHas.
    LaunchedEffect(e164) {
        if (mine || state.cardFor(e164) != null || looked != null) return@LaunchedEffect
        try {
            val result: LookupResult = graph.api.lookup(e164, if (onPhone) "call" else "search")
            graph.lookups[e164] = result
            looked = result
        } catch (e: OfflineException) {
            problem = "No internet. This shows what WhoHas knew last."
        } catch (e: ApiException) {
            problem = if (e.code == "quota_exceeded") "Today's searches are over. Try again tomorrow." else e.message
        }
    }

    val saved = state.isSaved(e164)
    val lastCall = state.lastCall(e164)
    val blocked = state.isBlocked(card) || (looked?.ref?.let { it in state.blockedRefs } == true)
    val takenOut = looked?.takenOut == true
    val name = if (mine) ShownName("Your number", NameSource.CONTACT) else state.nameFor(e164, card)
    val total = card?.total ?: 0
    val lowerBound = card?.totalIsLowerBound == true
    val knownCount = card?.knownTotal ?: 0
    val now = System.currentTimeMillis()
    val change = card?.let { state.changes.changes[it.ref] }?.takeIf { Changes.isToday(it, now, startOfToday(now)) }
    val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE
    val people = card?.let { AskOrder.order(state.knownPeople(it)) }.orEmpty()
    val askMode = !saved && !mine

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            if (!mine && !takenOut) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Share number") }, onClick = {
                            menu = false
                            context.shareText(Messages.share(name.text, e164, total, link, lowerBound))
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

        // Who it is.
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saved || mine) {
                Text(name.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(if (mine) shown(e164) else "${shown(e164)} · In your contacts", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { context.copyNumber(e164) })
            } else {
                if (lastCall != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CallKindIcon(lastCall, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(callLine(lastCall), style = MaterialTheme.typography.labelLarge, color = callKindColour(lastCall))
                    }
                }
                Text(shown(e164), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { context.copyNumber(e164) })
                Text("Not in your contacts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (name.source) {
                    NameSource.THEIR_OWN -> Chip("Their own name: ${name.text}", saffron = false)
                    NameSource.POPULAR -> {
                        val n = card?.popularNamePeople ?: 0
                        Chip(if (n >= 3) "$n people know it as “${name.text}”" else "People know it as “${name.text}”", saffron = false)
                    }
                    else -> Unit
                }
            }
            ErrorText(problem)
        }

        when {
            takenOut -> WhCard {
                Text("Hidden by the owner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("They removed this number from WhoHas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            blocked -> WhCard {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(50)) {
                    Text("Blocked", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                Text("You don't see each other on WhoHas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton("Unblock", {
                    val ref = card?.ref ?: looked?.ref
                    if (ref != null) scope.launch { problem = graph.model.unblock(ref) }
                })
            }
            else -> {
                // How many have it.
                WhCard(Modifier.clickable { showMeaning = true }, padding = 18.dp) {
                    if (total <= 0) {
                        Text(when {
                            mine -> "No one on WhoHas has your number yet"
                            saved -> HolderText.NO_ONE_ELSE
                            else -> "No one on WhoHas has this number yet"
                        }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (mine) LevelBlock(0)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(HolderText.grouped(total) + if (lowerBound) "+" else "", fontSize = 48.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(if (mine) (if (total == 1) "person has your number" else "people have your number")
                                else (if (total == 1 && !lowerBound) "person has this number" else "people have this number"),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val faces = people.map { it.first }.filterNot { Naming.looksLikeNumber(it.name) }
                            if (faces.isNotEmpty()) {
                                PeopleStack(faces.take(3).map { it.id to it.name }, ring = MaterialTheme.colorScheme.surface)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(HolderText.youKnowCount(knownCount), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = if (knownCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (change != null) Chip(HolderText.today(change.delta), Icons.Filled.KeyboardArrowUp)
                        val ownTotal = state.cards?.mine?.total ?: 0
                        if (mine) LevelBlock(total)
                        else CompareBox(Levels.of(total, lowerBound), ownTotal)
                    }
                }

                // One main action.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val member = state.onWhoHas(e164, card)
                    when {
                        mine -> PrimaryButton("Invite friends", onInviteFriends)
                        askMode && people.isNotEmpty() -> {
                            // Someone with a name first: contacts, then call-log people who gave one.
                            val first = people.map { it.first }.firstOrNull { !Naming.looksLikeNumber(it.name) } ?: people.first().first
                            // The whole name: first words like "Dr." or "Plumber" don't stand alone.
                            PrimaryButton(if (Naming.looksLikeNumber(first.name)) "Ask who this is" else "Ask ${first.name} who this is", {
                                context.messagePerson(first.e164, Messages.ask(e164, card?.name, card?.popularName))
                            }, icon = Icons.Filled.Send)
                        }
                        saved && PhoneNumbers.isIndianMobile(e164) && !member -> {
                            val who = name.text
                            WhCard(Modifier, color = MaterialTheme.colorScheme.secondaryContainer, edge = false) {
                                Text("$who isn't on WhoHas yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(if (total > 0) "${theirNumberLine(total, lowerBound)}. Let them know."
                                    else "Tell them about WhoHas, so you can ask each other about unknown callers.",
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                PrimaryButton(if (invited) "Told" else "Tell $who on WhatsApp", {
                                    context.messagePerson(e164, Messages.invite(total, link, lowerBound))
                                    invited = true
                                    scope.launch { graph.model.recordTell(e164) }
                                }, saffron = true, icon = Icons.Filled.Send)
                            }
                        }
                    }
                    if (!mine) NumberButtons(e164)
                    if (!saved && !mine && lastCall != null && lastCall.isIncoming) {
                        OutlinedButton(onClick = { context.dial(e164) }, modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(24.dp)) {
                            Icon(Icons.Filled.Call, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Call back")
                        }
                    }
                }

                // The list: people you know (contacts first, then call log), then a count of the rest.
                if (people.isNotEmpty() || total > knownCount) {
                    SectionLabel("People you know who have it")
                    WhCard(padding = 4.dp) {
                        val shownPeople = if (showAll) people else people.take(3)
                        for ((person, why) in shownPeople) {
                            // Someone only in the call log: the last call helps you place them.
                            val last = if (person.inContacts) null else person.e164?.let { state.lastCall(it) }
                            val line = when {
                                last == null -> why.hint
                                last.type == CallType.OUTGOING -> "In your call log · you called ${dayText(last.at)}"
                                else -> "In your call log · called you ${dayText(last.at)}"
                            }
                            NumberRow(
                                leading = { if (Naming.looksLikeNumber(person.name)) IconAvatar(Icons.Filled.Call, 36.dp) else Avatar(person.id, person.name, 36.dp) },
                                name = ShownName(person.name, NameSource.CONTACT),
                                line = line,
                                trailing = if (askMode) ({
                                    OutlinedButton(onClick = { context.messagePerson(person.e164, Messages.ask(e164, card?.name, card?.popularName)) },
                                        shape = RoundedCornerShape(22.dp), modifier = Modifier.height(44.dp)) { Text("Ask") }
                                }) else null,
                                onClick = person.e164?.let { p -> { onNumber(p) } },
                            )
                        }
                        if (!showAll && people.size > 3) {
                            TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("See all ${people.size}") }
                        }
                        val rest = total - knownCount
                        if (rest > 0) {
                            Text("+${HolderText.grouped(rest)}${if (lowerBound) "+" else ""} more ${if (rest == 1 && !lowerBound) "person" else "people"} you don't know",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                    }
                }

                // Every call with this number, with the call log's symbols.
                val calls = if (mine) emptyList() else state.callsWith(e164)
                if (calls.isNotEmpty()) {
                    SectionLabel(if (saved) "Your calls with ${name.text}" else "Your calls with this number")
                    WhCard(padding = 4.dp) {
                        val shownCalls = if (showAllCalls) calls else calls.take(5)
                        for (call in shownCalls) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CallKindIcon(call, 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(callKind(call), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                                    color = if (call.type == CallType.MISSED || call.type == CallType.REJECTED) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface)
                                Text(callWhen(call.at).replaceFirstChar { it.uppercase() } +
                                    (call.duration.takeIf { it > 0 && call.type != CallType.MISSED }?.let { " · ${durationText(it)}" } ?: ""),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!showAllCalls && calls.size > 5) {
                            TextButton(onClick = { showAllCalls = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("See all ${calls.size}") }
                        }
                    }
                }

                TextButton(onClick = { showMeaning = true }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("What does this mean?", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showMeaning) {
        ModalBottomSheet(onDismissRequest = { showMeaning = false }) {
            Meaning(card, name, mine, onDone = { showMeaning = false })
        }
    }
    if (confirmBlock) {
        ConfirmDialog(
            title = "Block ${name.text}?",
            text = "You won't see each other on WhoHas. Calls and messages still work.",
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

/** "What does this mean?": the card explained line by line, in the order it's read. */
@Composable
private fun Meaning(card: Card?, name: ShownName, mine: Boolean, onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("What does this mean?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        for ((bold, rest) in HolderText.meaning(card?.total ?: 0, card?.knownTotal ?: 0, card?.totalIsLowerBound == true, mine)) {
            MeaningRow(bold, rest)
        }
        for (level in Level.values()) MeaningRow(level.label, level.why)
        when (name.source) {
            NameSource.POPULAR -> MeaningRow("“${name.text}”", "The name 3 or more people have for this number in their contacts.")
            NameSource.THEIR_OWN -> MeaningRow("Their own name: ${name.text}", "The name they typed into WhoHas. Anyone can type any name.")
            else -> Unit
        }
        PrimaryButton("OK", onDone)
    }
}

@Composable
private fun MeaningRow(title: String, text: String) {
    Column {
        Text(title, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
