package app.foundwith.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foundwith.AppGraph
import app.foundwith.BuildConfig
import app.foundwith.core.CallEvent
import app.foundwith.core.CallType
import app.foundwith.core.Changes
import app.foundwith.core.HolderText
import app.foundwith.core.Messages
import app.foundwith.core.PhoneNumbers
import app.foundwith.core.SearchInput
import app.foundwith.core.SearchQuery
import app.foundwith.core.TellCandidate
import app.foundwith.core.TellList
import app.foundwith.data.ApiException
import app.foundwith.data.AppState
import app.foundwith.data.Card
import app.foundwith.data.Invites
import app.foundwith.data.LookupResult
import app.foundwith.data.OfflineException
import app.foundwith.data.SavedNumber
import app.foundwith.data.Setup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab { UPDATES, CALLS, CONTACTS }

/** The search box's answer. */
private sealed interface Found {
    data object Idle : Found
    data object Checking : Found
    data class Result(val result: LookupResult) : Found
    data class Problem(val title: String, val text: String) : Found
}

/**
 * Home (docs/WHOHAS_COPY.md): WhoHas and "Live · checked just now" at the top, the search box,
 * then Updates, Calls and Contacts as tabs at the bottom. Before the person allows anything, Home
 * shows their own number's count (only when someone has it) and the Allow card. The name and
 * notification cards wait until names have shown, so nothing stands before the first reward.
 */
@Composable
fun HomeScreen(
    graph: AppGraph,
    onNumber: (String) -> Unit,
    onSettings: () -> Unit,
    onInvite: () -> Unit,
    onHowItWorks: () -> Unit,
    onFix: () -> Unit,
) {
    val state by graph.model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.UPDATES) }
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<Found>(Found.Idle) }

    // Allow asks only for what the reward needs. Notifications are asked once names have shown.
    val allowPermissions = remember {
        buildList {
            add(Manifest.permission.READ_CONTACTS)
            if (BuildConfig.CALL_LOG) add(Manifest.permission.READ_CALL_LOG)
        }.toTypedArray()
    }
    val askAll = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        results.forEach { (permission, granted) -> if (granted) graph.store.clearDenials(permission) else graph.store.addDenial(permission) }
        scope.launch { graph.model.reloadPhone(); graph.model.startTakingPart() }
    }
    var asking by remember { mutableStateOf<String?>(null) }
    val askOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asking?.let { if (granted) graph.store.clearDenials(it) else graph.store.addDenial(it) }
        asking = null
        scope.launch {
            graph.model.reloadPhone()
            if (granted && state.setup == Setup.NOT_ALLOWED) graph.model.startTakingPart()
            else if (granted) app.foundwith.work.Scheduling.syncNow(context)
        }
    }
    fun ask(permission: String) {
        if (graph.store.denials(permission) >= 2) context.openAppSettings()
        else {
            asking = permission
            askOne.launch(permission)
        }
    }
    val turnOnNotifications = {
        graph.store.notifAsked = true
        if (Build.VERSION.SDK_INT >= 33 && graph.store.denials(Manifest.permission.POST_NOTIFICATIONS) < 2) {
            ask(Manifest.permission.POST_NOTIFICATIONS)
        } else context.openNotificationSettings()
    }

    // Look the number up as soon as it's complete.
    LaunchedEffect(query) {
        val q = SearchInput.classify(query)
        if (q !is SearchQuery.Mobile) {
            found = Found.Idle
            return@LaunchedEffect
        }
        delay(350)
        found = Found.Checking
        found = try {
            val result = graph.api.lookup(q.e164, "search")
            graph.lookups[q.e164] = result
            Found.Result(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OfflineException) {
            Found.Problem("No internet", "Search needs the internet.")
        } catch (e: ApiException) {
            if (e.code == "quota_exceeded") Found.Problem("Today's searches are over", "Search again tomorrow.")
            else Found.Problem("Couldn't check right now", e.message)
        }
    }

    // Names are on screen: from now on the name and notification cards may ask.
    LaunchedEffect(state.setup) { if (state.setup == Setup.READY) graph.store.firstNamesSeen = true }

    Scaffold(
        bottomBar = {
            if (state.setup == Setup.READY && query.isBlank()) {
                // Indigo for the selected tab: saffron is kept for what's new.
                val tabColors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val newCount = updateEntries(state).count { it.at > state.dotsSince }
                    NavigationBarItem(selected = tab == Tab.UPDATES, onClick = { tab = Tab.UPDATES }, label = { Text("Updates") }, colors = tabColors,
                        icon = {
                            BadgedBox(badge = { if (newCount > 0) Badge { Text(newCount.toString()) } }) {
                                Icon(Icons.Filled.Notifications, contentDescription = null)
                            }
                        })
                    if (BuildConfig.CALL_LOG) {
                        NavigationBarItem(selected = tab == Tab.CALLS, onClick = { tab = Tab.CALLS }, label = { Text("Calls") }, colors = tabColors,
                            icon = { Icon(Icons.Filled.Call, contentDescription = null) })
                    }
                    NavigationBarItem(selected = tab == Tab.CONTACTS, onClick = { tab = Tab.CONTACTS }, label = { Text("Contacts") }, colors = tabColors,
                        icon = { Icon(Icons.Filled.Person, contentDescription = null) })
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp,
            top = padding.calculateTopPadding())) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Wordmark()
                        if (state.setup != Setup.NOT_ALLOWED || state.cards != null) LiveStatus(state.cards?.savedAt, state.offline)
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                }
            }
            item { SearchBox(query, onChange = { query = it }) }

            if (query.isNotBlank()) {
                searchResults(state, query, found, onNumber)
                return@LazyColumn
            }

            item { Banners(graph, state, onFix, turnOnNotifications) }
            state.profile?.takeIf { it.invitedBy.isNotEmpty() && state.setup != Setup.READY }?.let { profile ->
                item { InvitedCard(profile.invitedBy, profile.invitedByCount, state.cards?.mine?.total ?: 0) }
            }

            when (state.setup) {
                Setup.NOT_ALLOWED -> {
                    val total = state.cards?.mine?.total ?: 0
                    // An empty count is no reward: lead with Allow until someone has the number.
                    if (total > 0) item {
                        HeroCard(state.profile?.phone?.let(::shown), total, countLabel(total), "Allow contacts to see who they are.")
                    }
                    item {
                        AllowCard(onAllow = {
                            graph.store.allowTapped = true
                            if (allowPermissions.all { graph.store.denials(it) >= 2 }) context.openAppSettings() else askAll.launch(allowPermissions)
                        }, onHowItWorks = onHowItWorks)
                    }
                }
                Setup.FINDING -> item { FindingCard(state, failed = false) {} }
                Setup.FIND_FAILED -> item { FindingCard(state, failed = true) { scope.launch { graph.model.retryFinding() } } }
                Setup.READY -> when (tab) {
                    Tab.UPDATES -> updatesTab(graph, state, onNumber, onInvite, turnOnNotifications)
                    Tab.CALLS -> callsTab(graph, state, onNumber, onAllow = { ask(Manifest.permission.READ_CALL_LOG) })
                    Tab.CONTACTS -> contactsTab(graph, state, onNumber, onAllow = { ask(Manifest.permission.READ_CONTACTS) })
                }
            }
        }
    }
}

private fun countLabel(total: Int): String = if (total == 1) "person has it" else "people have it"

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search a number or name") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun LazyListScope.searchResults(state: AppState, query: String, found: Found, onNumber: (String) -> Unit) {
    when (val q = SearchInput.classify(query)) {
        is SearchQuery.Name -> {
            val matches = state.contacts.filter { c -> c.shownName?.let { SearchInput.matches(it, q.text) } == true && c.e164 != null }
                .distinctBy { it.e164 }
            item { SectionLabel("Your contacts") }
            if (matches.isEmpty()) item { HintBox(Icons.Filled.Search, "No contact called \"${q.text.trim()}\"", "Type a number to search everyone.") }
            items(matches, key = { "s:" + it.e164 }) { c -> ContactRow(state, c, onNumber) }
        }
        is SearchQuery.Mobile -> item {
            when (found) {
                is Found.Result -> {
                    val r = found.result
                    val card = r.asCard()
                    NumberRow(
                        leading = { Avatar(r.ref, state.nameFor(r.number, card).text) },
                        name = state.nameFor(r.number, card),
                        line = if (r.takenOut) "Hidden by the owner" else state.lineFor(card, onYourPhone = state.isSaved(r.number)),
                        onClick = { onNumber(r.number) },
                    )
                }
                is Found.Problem -> HintBox(Icons.Filled.Warning, found.title, found.text)
                else -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking ${shown(q.e164)}…")
                }
            }
        }
        SearchQuery.Incomplete -> item { HintBox(Icons.Filled.Search, "Type all 10 digits", "Like 98451 72046") }
        SearchQuery.NotMobile -> item { HintBox(Icons.Filled.Search, "Only mobile numbers can be searched", "Like 98451 72046") }
        SearchQuery.Empty -> Unit
    }
}

@Composable
private fun HintBox(icon: ImageVector, title: String, text: String) {
    WhCard {
        Row {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Banners(graph: AppGraph, state: AppState, onFix: () -> Unit, onTurnOnNotifications: () -> Unit) {
    val now = System.currentTimeMillis()
    when {
        state.setup == Setup.READY && BuildConfig.CALL_LOG && graph.book.hasCallLogPermission() && graph.store.afterCalls &&
            graph.store.notifAsked && !graph.notifier.canPost() ->
            Banner(Icons.Filled.Notifications, "Notifications are off. You won't know who called.", "Turn on", warning = true, onAction = onTurnOnNotifications)
        state.lateCalls >= 2 && now - graph.store.fixDismissedAt > Changes.KEEP_MS ->
            Banner(Icons.Filled.Warning, "Your phone stopped WhoHas. Last ${state.lateCalls} calls weren't checked.", "Fix", warning = true, onAction = onFix)
    }
}

/** "Priya and 2 others invited you": the people who tapped Tell for this number. */
@Composable
private fun InvitedCard(names: List<String>, count: Int, total: Int) {
    val first = names.first()
    val others = maxOf(count, names.size) - 1
    WhCard(color = MaterialTheme.colorScheme.secondaryContainer, edge = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PeopleStack(names.take(3).map { it to it }, ring = MaterialTheme.colorScheme.secondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (others <= 0) "$first invited you" else "$first and $others ${if (others == 1) "other" else "others"} invited you",
                    fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                if (total > 0) Text("${HolderText.count(total)} on WhoHas ${if (total == 1) "has" else "have"} your number",
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** The Allow card: what WhoHas will do, then Allow. Contacts and call log only. */
@Composable
private fun AllowCard(onAllow: () -> Unit, onHowItWorks: () -> Unit) {
    WhCard(padding = 20.dp) {
        Text("See who you know", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text(if (BuildConfig.CALL_LOG) "Allow contacts and call log. WhoHas will:" else "Allow contacts. WhoHas will:",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TickLine("Show names of people you know, not just numbers")
        if (BuildConfig.CALL_LOG) TickLine("Tell you who knows a caller, right after the call")
        TickLine("Show when friends join, or more people get your number")
        PrimaryButton("Allow", onAllow)
        Row(Modifier.clickable(onClick = onHowItWorks).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("No one sees your contact list. If a friend checks a number you have, they see your name. How WhoHas works",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FindingCard(state: AppState, failed: Boolean, onTryNow: () -> Unit) {
    val total = state.cards?.mine?.total ?: 0
    if (total > 0) HeroCard(state.profile?.phone?.let(::shown), total, countLabel(total), null)
    WhCard {
        if (failed) {
            Text("Couldn't finish. We'll keep trying.")
            PrimaryButton("Try now", onTryNow)
        } else {
            Text("Checking your contacts… This takes a few seconds.")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- Updates: only what changed

private enum class UpdateKind { MINE_UP, COUNT_UP, JOINED, NOW_KNOWN }

private data class UpdateEntry(val key: String, val kind: UpdateKind, val ref: String, val e164: String?, val at: Long)

/** Every change in the last week, newest first: your count, contacts' counts, joins, callers now known. */
private fun updateEntries(state: AppState): List<UpdateEntry> {
    val mineRef = state.cards?.mine?.ref
    val log = state.changes
    val out = ArrayList<UpdateEntry>()
    for (ch in log.changes.values) {
        if (ch.ref in state.blockedRefs) continue
        if (ch.ref == mineRef) out += UpdateEntry("mine", UpdateKind.MINE_UP, ch.ref, state.profile?.phone, ch.at)
        else state.index.byRef[ch.ref]?.e164?.let { out += UpdateEntry("count:${ch.ref}", UpdateKind.COUNT_UP, ch.ref, it, ch.at) }
    }
    for ((ref, at) in log.joined) {
        if (ref in state.blockedRefs) continue
        state.index.byRef[ref]?.e164?.let { out += UpdateEntry("joined:$ref", UpdateKind.JOINED, ref, it, at) }
    }
    for (gain in log.gains.values) {
        if (gain.ref == mineRef || gain.ref in state.blockedRefs) continue
        val e164 = state.index.byRef[gain.ref]?.e164 ?: continue
        if (state.isSaved(e164)) continue           // a contact's change is told by its count
        out += UpdateEntry("known:${gain.ref}", UpdateKind.NOW_KNOWN, gain.ref, e164, gain.at)
    }
    return out.sortedByDescending { it.at }
}

private fun LazyListScope.updatesTab(graph: AppGraph, state: AppState, onNumber: (String) -> Unit, onInvite: () -> Unit,
                                     onTurnOnNotifications: () -> Unit) {
    val now = System.currentTimeMillis()
    val mine = state.cards?.mine
    item { MineHero(state, mine, onNumber, onInvite) }
    if (BuildConfig.CALL_LOG && graph.store.firstNamesSeen && !graph.store.notifAsked && !graph.notifier.canPost()) {
        item { NotificationsCard(onTurnOn = onTurnOnNotifications, onLater = { graph.store.notifAsked = true }) }
    }
    item { TellCard(graph, state, onInvite) }

    val entries = updateEntries(state)
    if (entries.isEmpty()) {
        item {
            if (graph.store.lastOpenedAt == 0L) {
                EmptyState(Icons.Filled.CheckCircle, "Your updates will show here",
                    "New people with your number, friends who join, and changes in your contacts. We check every few hours.")
            } else {
                EmptyState(Icons.Filled.CheckCircle, "No new changes",
                    "When more people get your number, or your contacts' numbers, it shows here.")
            }
        }
    } else {
        val today = startOfToday(now)
        val yesterday = today - 24 * 3600_000L
        val groups = listOf(
            "Today" to entries.filter { it.at >= today },
            "Yesterday" to entries.filter { it.at in yesterday until today },
            "This week" to entries.filter { it.at < yesterday },
        )
        for ((label, rows) in groups) {
            if (rows.isEmpty()) continue
            item(key = "label:$label") { SectionLabel(label) }
            items(rows, key = { it.key }) { entry -> UpdateItem(state, entry, entry.at > state.dotsSince, onNumber) }
        }
    }
    if (graph.store.firstNamesSeen && state.profile?.name == null && !graph.store.nameAsked) item { NameCard(graph) }
}

/** Your number, big, with who you know and this week's change. */
@Composable
private fun MineHero(state: AppState, mine: Card?, onNumber: (String) -> Unit, onInvite: () -> Unit) {
    val total = mine?.total ?: 0
    val phone = state.profile?.phone
    if (mine == null || total <= 0) {
        WhCard(color = MaterialTheme.colorScheme.primaryContainer, edge = false) {
            Text("Your number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("No one on WhoHas has your number yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            OutlinedButton(onClick = onInvite, shape = RoundedCornerShape(22.dp)) { Text("Invite friends") }
        }
        return
    }
    val names = state.knownOrdered(mine)
    val week = state.changes.changes[mine.ref]?.delta?.takeIf { it > 0 }
    HeroCard(
        number = phone?.let(::shown),
        count = total,
        countLabel = countLabel(total),
        line = HolderText.youKnowNames(names, mine.knownTotal) ?: "None of them are in your phone yet",
        people = mine.known.take(4).map { it.id to state.personName(it) },
        chip = week?.let { HolderText.thisWeek(it) },
        onClick = phone?.let { { onNumber(it) } },
    )
}

@Composable
private fun UpdateItem(state: AppState, entry: UpdateEntry, isNew: Boolean, onNumber: (String) -> Unit) {
    val e164 = entry.e164
    val card = e164?.let { state.cardFor(it) }
    val name = e164?.let { state.nameFor(it, card) }
    val (title, line) = when (entry.kind) {
        UpdateKind.MINE_UP -> {
            val ch = state.changes.changes[entry.ref]
            val gained = state.changes.gains[entry.ref]?.ids.orEmpty()
            val mine = state.cards?.mine
            val newNames = gained.mapNotNull { id -> mine?.known?.firstOrNull { it.id == id }?.let { state.personName(it) } }
            HolderText.mineUp(ch?.delta ?: 1) to when (newNames.size) {
                0 -> null
                1 -> "One is ${newNames[0]}"
                2 -> "${newNames[0]} and ${newNames[1]} are among them"
                else -> "${newNames[0]}, ${newNames[1]} and ${newNames.size - 2} more are among them"
            }
        }
        UpdateKind.COUNT_UP -> {
            val ch = state.changes.changes[entry.ref]
            (name?.text ?: "") to (ch?.let { HolderText.crossed(it.before, it.now) ?: HolderText.change(it.now, it.delta) })
        }
        UpdateKind.JOINED -> {
            val hasYours = state.cards?.mine?.known?.any { it.contactRef == entry.ref } == true
            "${name?.text ?: "A contact"} joined WhoHas" to (if (hasYours) "Has your number" else null)
        }
        UpdateKind.NOW_KNOWN -> {
            val ids = state.changes.gains[entry.ref]?.ids.orEmpty()
            val who = ids.firstNotNullOfOrNull { id -> card?.known?.firstOrNull { it.id == id }?.let { state.personName(it) } }
            (name?.text ?: "") to (who?.let { "Called you · now known to $it" })
        }
    }
    val lead: @Composable () -> Unit = when {
        entry.kind == UpdateKind.MINE_UP -> ({ IconAvatar(Icons.Filled.KeyboardArrowUp, accent = true) })
        name == null || name.isNumber -> ({ IconAvatar(Icons.Filled.Phone) })
        else -> ({ Avatar(e164 ?: entry.ref, name.text) })
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = e164 != null) { e164?.let(onNumber) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium,
                color = if (entry.kind == UpdateKind.JOINED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(whenText(entry.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isNew) {
                Spacer(Modifier.height(6.dp))
                NewDot()
            }
        }
    }
}

/** "Tell them who has their number": the people you call most who aren't on WhoHas, with real counts. */
@Composable
private fun TellCard(graph: AppGraph, state: AppState, onSeeAll: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hiddenAt by remember { mutableStateOf(graph.store.tellHiddenAt) }
    var list by remember { mutableStateOf<List<TellCandidate>>(emptyList()) }
    var told by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(state.contacts, state.cards) {
        val now = System.currentTimeMillis()
        val calls = graph.book.calls(sinceMs = now - TellList.WINDOW_DAYS * 24 * 3600_000L)
            .mapNotNull { c -> c.raw?.takeIf { it.isNotBlank() }?.let { it to c.at } }
        val members = state.cards?.circle?.memberRefs.orEmpty().toSet()
        val onWhoHas = state.index.byRef.filterKeys { it in members }.values.map { it.e164 }.toSet()
        val counts = state.contacts.mapNotNull { it.e164 }.distinct().associateWith { e ->
            state.cardFor(e)?.let { it.total to it.totalIsLowerBound } ?: (0 to false)
        }
        list = TellList.pick(graph.book.contactsForInvite(), calls, onWhoHas, counts, graph.store.toldAt(), now)
    }
    if (list.isEmpty() || TellList.hidden(hiddenAt, System.currentTimeMillis())) return
    val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE
    WhCard {
        Row(verticalAlignment = Alignment.Top) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(10.dp).size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Tell them who has their number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text("People you call most · not on WhoHas yet", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (person in list.take(3)) {
            TellRow(person, told = person.e164 in told) {
                context.messagePerson(person.e164, Messages.invite(person.others, link, person.lowerBound))
                told = told + person.e164
                scope.launch { graph.model.recordTell(person.e164) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSeeAll) {
                Text(if (list.size > 3) "See all ${list.size}" else "Invite more", fontWeight = FontWeight.Bold)
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val now = System.currentTimeMillis()
                graph.store.tellHiddenAt = now
                hiddenAt = now
            }) { Text("Hide", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** One person on the Tell card or the Invite page. */
@Composable
fun TellRow(person: TellCandidate, told: Boolean, onTell: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(person.e164, person.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (person.others > 0) HolderText.haveThis(person.others, person.lowerBound) else "No one on WhoHas has it yet",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        TellButton(told, person.name, onTell)
    }
}

@Composable
private fun NotificationsCard(onTurnOn: () -> Unit, onLater: () -> Unit) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed) return
    WhCard {
        Text("Turn on notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text("Get told who knows a caller right after a missed call.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Turn on", { dismissed = true; onTurnOn() }, Modifier.weight(1f))
            OutlinedButton(onClick = { dismissed = true; onLater() }, modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(26.dp)) { Text("Later") }
        }
    }
}

/** Shown once, after names: "Add your name", filled in from the phone's own Me card when it has one. */
@Composable
private fun NameCard(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed) return
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (name.isEmpty()) graph.book.ownerName()?.let { name = it.take(40) } }
    WhCard {
        Text("Add your name", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text("People who don't have you in their contacts will see this name.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
            placeholder = { Text("Like Raju Sharma") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        ErrorText(error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save", {
                saving = true
                scope.launch {
                    error = graph.model.setName(name)
                    saving = false
                    if (error == null) graph.store.nameAsked = true
                }
            }, Modifier.weight(1f), enabled = name.isNotBlank() && !saving)
            OutlinedButton(onClick = { graph.store.nameAsked = true; dismissed = true }, modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(26.dp)) { Text("Later") }
        }
    }
}

// ---------------------------------------------------------------- Calls

private fun LazyListScope.callsTab(graph: AppGraph, state: AppState, onNumber: (String) -> Unit, onAllow: () -> Unit) {
    if (!graph.book.hasCallLogPermission()) {
        item {
            if (graph.store.denials(Manifest.permission.READ_CALL_LOG) >= 2) {
                EmptyState(Icons.Filled.Lock, "Call log is off", "Open Settings › Permissions › Call logs › Allow.", "Open Settings", onAllow)
            } else {
                EmptyState(Icons.Filled.Call, "See who has your callers' numbers", "Allow the call log.", "Allow", onAllow)
            }
        }
        return
    }
    if (state.calls.isEmpty()) {
        item { EmptyState(Icons.Filled.Call, "No calls yet", "Your calls show here, with who has each number.") }
        return
    }
    items(state.calls, key = { "c:" + it.id }) { call -> CallRow(state, call, onNumber) }
}

@Composable
private fun CallRow(state: AppState, call: CallEvent, onNumber: (String) -> Unit) {
    val e164 = call.e164
    val missed = call.type == CallType.MISSED || call.type == CallType.REJECTED
    if (e164 == null || call.isHidden) {
        NumberRow(leading = { IconAvatar(Icons.Filled.Lock) }, name = app.foundwith.core.ShownName("Private number", app.foundwith.core.NameSource.NUMBER),
            line = "Can't check hidden numbers", sub = callLine(call))
        return
    }
    val card = state.cardFor(e164)
    val name = state.nameFor(e164, card)
    val blocked = state.refFor(e164)?.let { it in state.blockedRefs } == true
    NumberRow(
        leading = { if (name.isNumber) IconAvatar(Icons.Filled.Phone) else Avatar(e164, name.text) },
        name = name,
        line = if (blocked) "Blocked" else state.callerLine(card, onYourPhone = state.isSaved(e164)),
        sub = callLine(call),
        trailing = if (missed) ({ Icon(Icons.Filled.Call, contentDescription = "Missed call", tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)) }) else null,
        onClick = { onNumber(e164) },
    )
}

// ---------------------------------------------------------------- Contacts

private fun LazyListScope.contactsTab(graph: AppGraph, state: AppState, onNumber: (String) -> Unit, onAllow: () -> Unit) {
    if (!graph.book.hasContactsPermission()) {
        item {
            if (graph.store.denials(Manifest.permission.READ_CONTACTS) >= 2) {
                EmptyState(Icons.Filled.Lock, "Contacts are off", "Open Settings › Permissions › Contacts › Allow.", "Open Settings", onAllow)
            } else {
                EmptyState(Icons.Filled.Person, "See who has your contacts' numbers", "Allow contacts.", "Allow", onAllow)
            }
        }
        return
    }
    val all = state.contacts.filter { it.e164 != null && !state.isMine(it.e164!!) }.distinctBy { it.e164 }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shownName ?: it.raw })
    if (all.isEmpty()) {
        item { EmptyState(Icons.Filled.Person, "No contacts on this phone", "Contacts you save show here.") }
        return
    }
    val (found, onlyYou) = all.partition { (state.cardFor(it.e164!!)?.total ?: 0) > 0 }
    items(found, key = { "k:" + it.e164 }) { c -> ContactRow(state, c, onNumber) }
    if (onlyYou.isNotEmpty()) {
        item { SectionLabel("No one else has these yet") }
        items(onlyYou, key = { "o:" + it.e164 }) { c -> ContactRow(state, c, onNumber, offerTell = true) }
    }
}

@Composable
private fun ContactRow(state: AppState, contact: SavedNumber, onNumber: (String) -> Unit, offerTell: Boolean = false) {
    val context = LocalContext.current
    val e164 = contact.e164 ?: return
    val card = state.cardFor(e164)
    val name = state.nameFor(e164, card)
    val canTell = offerTell && PhoneNumbers.isIndianMobile(e164) && !state.onWhoHas(e164, card)
    var told by remember { mutableStateOf(false) }
    NumberRow(
        leading = { Avatar(e164, name.text) },
        name = name,
        line = state.lineFor(card, onYourPhone = true),
        trailing = if (canTell) ({
            TellButton(told, name.text) {
                val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE
                context.messagePerson(e164, Messages.invite(card?.total ?: 0, link))
                told = true
            }
        }) else null,
        onClick = { onNumber(e164) },
    )
}
