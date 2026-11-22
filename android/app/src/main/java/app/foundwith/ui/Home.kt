package app.foundwith.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.focus.onFocusChanged
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
import app.foundwith.core.Changes
import app.foundwith.core.HolderText
import app.foundwith.core.Messages
import app.foundwith.core.Naming
import app.foundwith.R
import app.foundwith.core.PhoneNumbers
import app.foundwith.core.SearchInput
import app.foundwith.core.SearchQuery
import app.foundwith.core.TellCandidate
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

private enum class Tab { UPDATES, CONTACTS, CALLS }

/** The search box's answer. */
private sealed interface Found {
    data object Idle : Found
    data object Checking : Found
    data class Result(val result: LookupResult) : Found
    data class Problem(val title: String, val text: String) : Found
}

/**
 * Home (docs/WHOHAS_COPY.md): WhoHas, "Live · checked just now" and Share in group at the top, the
 * search box, then Updates, Contacts and Call log as tabs at the bottom. Before the person allows anything, Home
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
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    // Back closes the search first (it used to close the app), then goes back to Updates.
    BackHandler(enabled = searching || query.isNotEmpty()) {
        query = ""
        focus.clearFocus()
        searching = false
    }
    BackHandler(enabled = !searching && query.isEmpty() && tab != Tab.UPDATES) { tab = Tab.UPDATES }

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

    val shareGroup = {
        val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE
        context.shareImage(R.drawable.share_whohas, Messages.groupShare(link), "Share WhoHas")
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.CompositionLocalProvider(LocalNumberMenu provides { e164: String -> menuFor = e164 }) {
    Scaffold(
        bottomBar = {
            if (state.setup == Setup.READY && query.isBlank()) {
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
                    NavigationBarItem(selected = tab == Tab.CONTACTS, onClick = { tab = Tab.CONTACTS }, label = { Text("Contacts") }, colors = tabColors,
                        icon = { Icon(Icons.Filled.Person, contentDescription = null) })
                    if (BuildConfig.CALL_LOG) {
                        NavigationBarItem(selected = tab == Tab.CALLS, onClick = { tab = Tab.CALLS }, label = { Text("Call log") }, colors = tabColors,
                            icon = { Icon(Icons.Filled.Call, contentDescription = null) })
                    }
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
                    // Share in group: solid gold, on every Home screen (22 Nov 2026).
                    Button(
                        onClick = shareGroup,
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(44.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share in group", fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                }
            }
            item { SearchBox(query, onChange = { query = it }, onFocus = { searching = it }) }

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
                    // Your number and your level first, even at 0: "Not popular · 10 more people and you're Popular".
                    item { HeroBefore(state, "Allow contacts to see who they are.") }
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
                    Tab.UPDATES -> updatesTab(graph, state, onNumber, onInvite, shareGroup, turnOnNotifications,
                        onSeeContacts = { tab = Tab.CONTACTS }, onSeeCalls = { tab = Tab.CALLS })
                    Tab.CONTACTS -> contactsTab(graph, state, onNumber, onAllow = { ask(Manifest.permission.READ_CONTACTS) })
                    Tab.CALLS -> callsTab(graph, state, onNumber, onAllow = { ask(Manifest.permission.READ_CALL_LOG) })
                }
            }
        }
    }
    }
    menuFor?.let { e164 ->
        NumberMenuSheet(e164, state.nameFor(e164, state.cardFor(e164)).text, onOpen = { onNumber(e164) }, onDismiss = { menuFor = null })
    }
}

private fun countLabel(total: Int): String = if (total == 1) "person has it" else "people have it"

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, onFocus: (Boolean) -> Unit) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged { onFocus(it.isFocused) },
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
                        tag = if (r.takenOut) null else rowTag(r.total, r.totalIsLowerBound),
                        onClick = { onNumber(r.number) },
                        e164 = r.number,
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
        SearchQuery.Foreign -> item { HintBox(Icons.Filled.Search, "WhoHas checks only Indian numbers", "Like 98451 72046") }
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
        state.setup == Setup.READY && BuildConfig.CALL_LOG && graph.book.hasCallLogPermission() && (graph.store.notifyUnknownCalls || graph.store.notifyOtherCalls) &&
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
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
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
        Text("See who has each number", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (BuildConfig.CALL_LOG) "Allow contacts and call log. Then you can:" else "Allow contacts. Then you can:",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TickLine("Find out who has your number")
        TickLine("See who has each number on your phone")
        TickLine("Know when something new happens or changes")
        if (BuildConfig.CALL_LOG) TickLine("After a call, get a notification if someone has the caller's number")
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
    HeroBefore(state, null)
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

// ---------------------------------------------------------------- Updates: everything found, what changed on top

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

/** What changed since the app was last open, one row per number (your own number's change is on its card). */
private fun newEntries(state: AppState): List<UpdateEntry> =
    updateEntries(state).filter { it.at > state.dotsSince && it.kind != UpdateKind.MINE_UP && it.e164 != null }.distinctBy { it.e164 }

/** Numbers from your calls that aren't in your contacts and that someone on WhoHas has: people you know first, then the latest call. */
private fun foundCallers(state: AppState, skip: Set<String>): List<Pair<String, CallEvent>> {
    val seen = HashSet<String>()
    val out = ArrayList<Pair<String, CallEvent>>()
    for (call in state.calls) {
        val e164 = call.e164 ?: continue
        if (!seen.add(e164) || e164 in skip || state.isMine(e164) || state.isSaved(e164)) continue
        val card = state.cardFor(e164) ?: continue
        if (card.total <= 0 || state.isBlocked(card)) continue
        out += e164 to call
    }
    return out.sortedWith(compareByDescending<Pair<String, CallEvent>> { (state.cardFor(it.first)?.knownTotal ?: 0) > 0 }.thenByDescending { it.second.at })
}

/** Contacts that someone on WhoHas has: people you know first, then the most people. */
private fun foundContacts(state: AppState, skip: Set<String>): List<SavedNumber> =
    state.contacts.asSequence()
        .filter { c -> c.e164 != null && c.e164!! !in skip && !state.isMine(c.e164!!) }
        .distinctBy { it.e164 }
        .filter { c -> state.cardFor(c.e164!!)?.let { it.total > 0 && !state.isBlocked(it) } == true }
        .sortedWith(compareByDescending<SavedNumber> { (state.cardFor(it.e164!!)?.knownTotal ?: 0) > 0 }
            .thenByDescending { state.cardFor(it.e164!!)?.total ?: 0 })
        .toList()

private fun LazyListScope.updatesTab(
    graph: AppGraph,
    state: AppState,
    onNumber: (String) -> Unit,
    onInvite: () -> Unit,
    onShareGroup: () -> Unit,
    onTurnOnNotifications: () -> Unit,
    onSeeContacts: () -> Unit,
    onSeeCalls: () -> Unit,
) {
    val mine = state.cards?.mine
    if (graph.store.levelReachedRecently(System.currentTimeMillis()) && (mine?.total ?: 0) >= app.foundwith.core.Levels.POPULAR_FROM) {
        item(key = "level-up") { LevelUpBanner(mine!!.total, onShareGroup) }
    }
    item { MineHero(state, mine, onNumber) }
    if (BuildConfig.CALL_LOG && graph.store.firstNamesSeen && !graph.store.notifAsked && !graph.notifier.canPost()) {
        item { NotificationsCard(onTurnOn = onTurnOnNotifications, onLater = { graph.store.notifAsked = true }) }
    }
    val news = newEntries(state)
    val skip = news.mapNotNullTo(HashSet()) { it.e164 }
    val callers = foundCallers(state, skip)
    val contacts = foundContacts(state, skip)

    if (news.isNotEmpty()) {
        item { SectionLabel("New") }
        item(key = "new") { WhCard(padding = 4.dp) { news.forEach { NewRow(state, it, onNumber) } } }
    }
    if (callers.isNotEmpty()) {
        item { SectionLabel("From your calls") }
        item(key = "callers") {
            WhCard(padding = 4.dp) {
                callers.take(5).forEach { (e164, call) -> FoundCallerRow(state, e164, call, onNumber) }
                if (callers.size > 5) SeeAll(onSeeCalls)
            }
        }
    }
    if (contacts.isNotEmpty()) {
        item { SectionLabel("Your contacts") }
        item(key = "contacts") {
            WhCard(padding = 4.dp) {
                contacts.take(5).forEach { FoundContactRow(state, it, onNumber) }
                if (contacts.size > 5) SeeAll(onSeeContacts)
            }
        }
    }
    if (news.isEmpty() && callers.isEmpty() && contacts.isEmpty()) item { NothingFoundCard(state) }
    if (graph.store.firstNamesSeen && state.profile?.name == null && !graph.store.nameAsked) item { NameCard(graph) }
}

@Composable
private fun SeeAll(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text("See all", fontWeight = FontWeight.Bold)
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** "None of your numbers are with people on WhoHas yet", with Share in group. */
@Composable
private fun NothingFoundCard(state: AppState) {
    val context = LocalContext.current
    WhCard(padding = 20.dp) {
        Text("None of your numbers are with people on WhoHas yet", style = MaterialTheme.typography.titleLarge)
        Text(if (BuildConfig.CALL_LOG) "When people you know join, every number in your contacts and calls that they have shows here, with who has it."
            else "When people you know join, every number in your contacts that they have shows here, with who has it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton("Share in group", {
            val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE
            context.shareImage(R.drawable.share_whohas, Messages.groupShare(link), "Share WhoHas")
        }, icon = Icons.Filled.Share)
    }
}

/** A number from your calls: the symbol of the last call, what kind and when, and who has it. */
@Composable
private fun FoundCallerRow(state: AppState, e164: String, call: CallEvent, onNumber: (String) -> Unit) {
    val card = state.cardFor(e164)
    InfoRow(
        leading = { CallKindIcon(call) },
        name = state.nameFor(e164, card),
        sub = callLine(call), subColor = callKindColour(call),
        line = state.callerLine(card, onYourPhone = false),
        lineColor = knownColour(card),
        onClick = { onNumber(e164) },
        e164 = e164,
        tag = rowTag(state.cardFor(e164)?.total, state.cardFor(e164)?.totalIsLowerBound == true),
    )
}

@Composable
private fun FoundContactRow(state: AppState, contact: SavedNumber, onNumber: (String) -> Unit) {
    val e164 = contact.e164 ?: return
    val card = state.cardFor(e164)
    val name = state.nameFor(e164, card)
    InfoRow(
        leading = { Avatar(e164, name.text) },
        name = name,
        line = state.lineFor(card, onYourPhone = true),
        lineColor = knownColour(card),
        onClick = { onNumber(e164) },
        e164 = e164,
        tag = rowTag(state.cardFor(e164)?.total, state.cardFor(e164)?.totalIsLowerBound == true),
    )
}

/** Gold when you know someone who has the number, grey when it's only a count. */
@Composable
private fun knownColour(card: Card?): androidx.compose.ui.graphics.Color =
    if ((card?.knownTotal ?: 0) > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

/** Your number, big, with who you know, this week's change and your level. */
@Composable
private fun MineHero(state: AppState, mine: Card?, onNumber: (String) -> Unit) {
    val total = mine?.total ?: 0
    val phone = state.profile?.phone
    if (mine == null || total <= 0) {
        EmptyHero(phone?.let(::shown))
        return
    }
    val names = state.knownOrdered(mine)
    val gained = state.changes.changes[mine.ref]?.takeIf { it.delta > 0 }
    val newNames = state.changes.gains[mine.ref]?.ids.orEmpty()
        .mapNotNull { id -> mine.known.firstOrNull { it.id == id }?.let { state.personName(it) } }
        .filterNot { Naming.looksLikeNumber(it) }
    HeroCard(
        number = phone?.let(::shown),
        count = total,
        countLabel = countLabel(total),
        line = HolderText.youKnowNames(names, mine.knownTotal) ?: "None of them are in your phone yet",
        people = mine.known.map { it.id to state.personName(it) }.filterNot { Naming.looksLikeNumber(it.second) }.take(4),
        chip = gained?.let { HolderText.mineUp(it.delta).removeSuffix(" have your number").removeSuffix(" has your number") +
            (newNames.firstOrNull()?.let { n -> " · One is $n" } ?: "") },
        onClick = phone?.let { { onNumber(it) } },
        extra = { LevelBlock(total) },
    )
}

/** Your number before the person allows anything, or while names are being found: the count and your level, no link. */
@Composable
private fun HeroBefore(state: AppState, line: String?) {
    val total = state.cards?.mine?.total ?: 0
    val phone = state.profile?.phone?.let(::shown)
    if (total <= 0) EmptyHero(phone)
    else HeroCard(phone, total, countLabel(total), line, extra = { LevelBlock(total) })
}

/** One number under New: what changed, with the gold dot. */
@Composable
private fun NewRow(state: AppState, entry: UpdateEntry, onNumber: (String) -> Unit) {
    val e164 = entry.e164 ?: return
    val card = state.cardFor(e164)
    val name = state.nameFor(e164, card)
    val saved = state.isSaved(e164)
    val call = state.lastCall(e164)
    val change: String? = when (entry.kind) {
        UpdateKind.COUNT_UP -> state.changes.changes[entry.ref]?.let { HolderText.crossed(it.before, it.now) ?: HolderText.change(it.now, it.delta) }
        UpdateKind.JOINED -> if (state.cards?.mine?.known?.any { it.contactRef == entry.ref } == true) "Joined WhoHas · has your number" else "Joined WhoHas"
        UpdateKind.NOW_KNOWN -> state.changes.gains[entry.ref]?.ids.orEmpty()
            .firstNotNullOfOrNull { id -> card?.known?.firstOrNull { it.id == id }?.let { state.personName(it) } }
            ?.let { "Now known to $it" }
        UpdateKind.MINE_UP -> null
    }
    InfoRow(
        leading = { if (!saved && call != null) CallKindIcon(call) else if (name.isNumber) IconAvatar(Icons.Filled.Phone) else Avatar(e164, name.text) },
        name = name,
        sub = if (saved) "In your contacts" else call?.let { callLine(it) },
        subColor = if (!saved && call != null) callKindColour(call) else MaterialTheme.colorScheme.onSurfaceVariant,
        line = change ?: state.callerLine(card, onYourPhone = saved),
        lineColor = if (entry.kind == UpdateKind.JOINED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        isNew = true,
        onClick = { onNumber(e164) },
        e164 = e164,
        tag = rowTag(state.cardFor(e164)?.total, state.cardFor(e164)?.totalIsLowerBound == true),
    )
}

/** "Their number is with 7 people on WhoHas"; "No one else on WhoHas has it yet". */
fun theirNumberLine(others: Int, lowerBound: Boolean): String =
    if (others <= 0) "No one else on WhoHas has it yet"
    else "Their number is with ${HolderText.count(others, lowerBound)} on WhoHas"

/** One person on the Invite page's "Let them know who has their number" list. */
@Composable
fun TellRow(person: TellCandidate, told: Boolean, onTell: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(person.e164, person.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(theirNumberLine(person.others, person.lowerBound),
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
        Text("Turn on notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        Text("Add your name", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

// ---------------------------------------------------------------- Call log

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
    val now = System.currentTimeMillis()
    state.calls.groupBy { dayHeading(it.at, now) }.forEach { (day, calls) ->
        item(key = "day:$day") { SectionLabel(day) }
        items(calls, key = { "c:" + it.id }) { call -> CallRow(state, call, onNumber) }
    }
}

@Composable
private fun CallRow(state: AppState, call: CallEvent, onNumber: (String) -> Unit) {
    val sub = callLine(call, withDay = false)
    val e164 = call.e164
    if (e164 == null) {
        val title = when {
            call.isHidden -> "Private number"
            else -> call.cachedName?.takeIf { it.isNotBlank() } ?: call.raw.orEmpty()
        }
        InfoRow(
            leading = { CallKindIcon(call) },
            name = app.foundwith.core.ShownName(title, app.foundwith.core.NameSource.NUMBER),
            sub = sub, subColor = callKindColour(call),
            line = when {
                call.isHidden -> "Can't check hidden numbers"
                call.isForeign -> ONLY_INDIAN
                else -> "WhoHas can't check this number"
            },
        )
        return
    }
    val card = state.cardFor(e164)
    val blocked = state.refFor(e164)?.let { it in state.blockedRefs } == true
    InfoRow(
        leading = { CallKindIcon(call) },
        name = state.nameFor(e164, card),
        sub = sub, subColor = callKindColour(call),
        line = if (blocked) "Blocked" else state.callerLine(card, onYourPhone = state.isSaved(e164)),
        lineColor = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant else knownColour(card),
        onClick = { onNumber(e164) },
        e164 = e164,
        tag = rowTag(state.cardFor(e164)?.total, state.cardFor(e164)?.totalIsLowerBound == true),
    )
}

// ---------------------------------------------------------------- Contacts: everyone in your phone, A to Z

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
    val all = state.contacts.filter { c -> c.e164?.let { !state.isMine(it) } ?: true }
        .distinctBy { it.e164 ?: ("raw:" + it.raw) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shownName ?: it.raw })
    if (all.isEmpty()) {
        item { EmptyState(Icons.Filled.Person, "No contacts on this phone", "Contacts you save show here.") }
        return
    }
    all.groupBy { c -> (c.shownName ?: c.raw).trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar()?.toString() ?: "#" }
        .forEach { (letter, people) ->
            item(key = "letter:$letter") { SectionLabel(letter) }
            items(people, key = { "k:" + (it.e164 ?: ("raw:" + it.raw)) }) { c -> ContactRow(state, c, onNumber) }
        }
}

@Composable
private fun ContactRow(state: AppState, contact: SavedNumber, onNumber: (String) -> Unit) {
    val context = LocalContext.current
    val e164 = contact.e164
    if (e164 == null) {
        val name = contact.shownName?.takeIf { it.isNotBlank() } ?: contact.raw
        InfoRow(
            leading = { Avatar(contact.raw, name) },
            name = app.foundwith.core.ShownName(name, app.foundwith.core.NameSource.CONTACT),
            line = if (PhoneNumbers.isForeign(contact.raw)) ONLY_INDIAN else "WhoHas can't check this number",
        )
        return
    }
    val card = state.cardFor(e164)
    val name = state.nameFor(e164, card)
    val total = card?.total ?: 0
    // No Share or Tell pill on these rows (22 Nov 2026): tapping opens the card, which has "Tell … on WhatsApp".
    InfoRow(
        leading = { Avatar(e164, name.text) },
        name = name,
        line = state.lineFor(card, onYourPhone = true),
        lineColor = knownColour(card),
        onClick = { onNumber(e164) },
        e164 = e164,
        tag = rowTag(state.cardFor(e164)?.total, state.cardFor(e164)?.totalIsLowerBound == true),
    )
}
