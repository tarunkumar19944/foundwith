package app.foundwith.ui

import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.rememberLazyListState
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
import app.foundwith.core.UpdatesOrder
import app.foundwith.core.UpdateWhat
import app.foundwith.core.UpdateEvent
import app.foundwith.core.UpdateItem
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foundwith.AppGraph
import app.foundwith.BuildConfig
import app.foundwith.core.DataNotice
import app.foundwith.core.CallEvent
import app.foundwith.core.Changes
import app.foundwith.core.HolderText
import app.foundwith.core.LinkJoins
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // Every number in Updates, and the ones marked new (in Updates, Call log and Contacts alike).
    val updates = remember(state) { updateItems(state) }
    val marks = remember(updates, state.dotsSince, state.seenNumbers) { state.marked(updates) }
    // How many of them are in Call log and in Contacts, for the red counts on those tabs.
    val callNumbers = remember(state.calls) { state.calls.mapNotNullTo(HashSet()) { it.e164 } }
    val inCalls = marks.keys.count { it in callNumbers }
    val inContacts = marks.keys.count { state.isSaved(it) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Call log first (Y, 22 Nov 2026): the app opens on it; Contacts in the contacts-only build.
    val homeTab = if (BuildConfig.CALL_LOG) Tab.CALLS else Tab.CONTACTS
    var tab by rememberSaveable { mutableStateOf(if (graph.openUpdates.value) Tab.UPDATES else homeTab) }
    // A notification's "Open Updates" switches to Updates, also when Home is already open.
    LaunchedEffect(Unit) {
        graph.openUpdates.collect { open -> if (open) { tab = Tab.UPDATES; graph.openUpdates.value = false } }
    }
    // The Updates badge counts what came since Updates was last looked at: opening Updates clears it,
    // and leaving Updates (another tab, or the app) counts what came in meanwhile as seen.
    var shownTab by rememberSaveable { mutableStateOf<Tab?>(null) }
    LaunchedEffect(tab) {
        if (tab == shownTab) return@LaunchedEffect
        if (shownTab == Tab.UPDATES) graph.model.leftUpdates()
        if (tab == Tab.UPDATES) graph.model.openedUpdates()
        shownTab = tab
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (tab == Tab.UPDATES) graph.model.leftUpdates() }
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<Found>(Found.Idle) }
    // A short number or another country's: looked up only after the Search button (23 Nov 2026).
    var askedFor by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // Each tab keeps its own place in its list (22 Nov 2026), so going back to Updates lands where you were.
    val updatesList = rememberLazyListState()
    val contactsList = rememberLazyListState()
    val callsList = rememberLazyListState()
    fun listFor(t: Tab) = when (t) { Tab.UPDATES -> updatesList; Tab.CONTACTS -> contactsList; Tab.CALLS -> callsList }
    // Search results have a list of their own (24 Nov 2026): they always start at the top, right under
    // the search box, and clearing the search returns each tab to where it was.
    val searchList = rememberLazyListState()
    // The WhoHas row folds away as the list scrolls up and comes back at the top; the search box
    // below it stays fixed, outside the list, so one tap always opens the keyboard.
    val header = remember { FoldingHeader() }
    /** A tab at the bottom: tapping the one you're on jumps to its top. */
    fun pick(t: Tab) {
        if (tab == t) scope.launch { listFor(t).animateScrollToItem(0); header.open() } else tab = t
    }

    // Back closes the search first (it used to close the app), then goes back to Call log.
    BackHandler(enabled = searching || query.isNotEmpty()) {
        query = ""
        focus.clearFocus()
        searching = false
    }
    BackHandler(enabled = !searching && query.isEmpty() && tab != homeTab) {
        tab = homeTab
    }

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

    // Look the number up as soon as it's complete (a 10-digit Indian number), or when Search is tapped.
    LaunchedEffect(query, askedFor) {
        val q = SearchInput.classify(query)
        if (q !is SearchQuery.Mobile || (!q.auto && askedFor != q.e164)) {
            found = Found.Idle
            return@LaunchedEffect
        }
        if (q.auto) delay(350)
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
                Column {
                // Your number stays here, the same on every tab; only the list above changes.
                val mine = state.cards?.mine
                YourNumberBar(
                    total = mine?.total ?: 0,
                    up = mine?.ref?.let { state.changes.changes[it] }?.delta?.takeIf { it > 0 } ?: 0,
                    onClick = state.profile?.phone?.let { phone -> { onNumber(phone) } },
                    onShare = shareGroup,
                )
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    // Call log · Contacts · Updates (Y, 22 Nov 2026). Each carries a red count (Y, 23 Nov):
                    // how many numbers in it changed that haven't been seen in Updates or on their card yet.
                    if (BuildConfig.CALL_LOG) {
                        NavigationBarItem(selected = tab == Tab.CALLS, onClick = { pick(Tab.CALLS) }, label = { Text("Call log") }, colors = tabColors,
                            modifier = Modifier.semantics { if (inCalls > 0) stateDescription = "$inCalls new" },
                            icon = { TabBadge(inCalls) { Icon(Icons.Filled.Call, contentDescription = null) } })
                    }
                    NavigationBarItem(selected = tab == Tab.CONTACTS, onClick = { pick(Tab.CONTACTS) }, label = { Text("Contacts") }, colors = tabColors,
                        modifier = Modifier.semantics { if (inContacts > 0) stateDescription = "$inContacts new" },
                        icon = { TabBadge(inContacts) { Icon(Icons.Filled.Person, contentDescription = null) } })
                    // Someone not on this phone who joined with your link has no row of their own, only the note on top of Updates.
                    val unseen = if (tab == Tab.UPDATES) 0 else UpdatesOrder.newSince(updates, state.updatesSeenAt, state.seenNumbers).size +
                        state.linkJoins().count { it.e164 == null && state.updatesSeenAt > 0 && (it.at ?: 0L) > state.updatesSeenAt }
                    NavigationBarItem(selected = tab == Tab.UPDATES, onClick = { pick(Tab.UPDATES) }, label = { Text("Updates") }, colors = tabColors,
                        modifier = Modifier.semantics { if (unseen > 0) stateDescription = "$unseen new" },
                        icon = {
                            BadgedBox(badge = {
                                if (unseen > 0) Badge(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) {
                                    Text(if (unseen > 99) "99+" else unseen.toString(), fontWeight = FontWeight.Bold)
                                }
                            }) {
                                Icon(Icons.Filled.Notifications, contentDescription = null)
                            }
                        })
                }
                }
            }
        },
    ) { padding ->
        val list = if (query.isNotBlank()) searchList else listFor(tab)
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).nestedScroll(header.connection)) {
            header.Folding {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Wordmark()
                        if (state.setup != Setup.NOT_ALLOWED || state.cards != null) LiveStatus(state.cards?.savedAt, state.offline)
                    }
                    // Share in group: solid blue, on every Home screen (22 Nov 2026).
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
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = if (header.folded) 6.dp else 0.dp) {
                Column {
                    SearchBox(query, onChange = { query = it }, onFocus = { searching = it })
                    if (header.folded) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)) {
            if (query.isNotBlank()) {
                searchResults(state, query, found, askedFor, onAsk = { askedFor = it }, onNumber)
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
                    Tab.UPDATES -> updatesTab(graph, state, updates, onNumber, onInvite, shareGroup, turnOnNotifications)
                    Tab.CONTACTS -> contactsTab(graph, state, marks, onNumber, onAllow = { ask(Manifest.permission.READ_CONTACTS) })
                    Tab.CALLS -> callsTab(graph, state, marks, onNumber, onAllow = { ask(Manifest.permission.READ_CALL_LOG) })
                }
            }
        }
        }
    }
    }
    menuFor?.let { e164 ->
        NumberMenuSheet(e164, state.nameFor(e164, state.cardFor(e164)).text, onOpen = { onNumber(e164) }, onDismiss = { menuFor = null })
    }
}

/**
 * The WhoHas row on top of Home (24 Nov 2026): it folds away as the list scrolls up and opens again
 * once the list is back at its top, like WhatsApp's. It sits outside the list, as the search box
 * under it does, so scrolling and the keyboard never rebuild the search box (it used to take 4 or
 * 5 taps when the list was scrolled down).
 */
private class FoldingHeader {
    var height by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)        // 0 (open) to -height (folded)
    val folded: Boolean get() = height > 0f && offset <= -height + 1f

    fun open() { offset = 0f }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f || height <= 0f) return Offset.Zero
            val next = (offset + available.y).coerceIn(-height, 0f)
            val used = next - offset
            offset = next
            return Offset(0f, used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f || height <= 0f) return Offset.Zero
            val next = (offset + available.y).coerceIn(-height, 0f)
            val used = next - offset
            offset = next
            return Offset(0f, used)
        }
    }

    @Composable
    fun Folding(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
            height = placeable.height.toFloat()
            val shown = (placeable.height + offset).roundToInt().coerceIn(0, placeable.height)
            layout(placeable.width, shown) { placeable.place(0, offset.roundToInt()) }
        }) { content() }
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

private fun LazyListScope.searchResults(state: AppState, query: String, found: Found, askedFor: String?,
                                        onAsk: (String) -> Unit, onNumber: (String) -> Unit) {
    when (val q = SearchInput.classify(query)) {
        is SearchQuery.Mobile -> if (!q.auto && askedFor != q.e164) item {
            // Short and other countries' numbers wait for this button, so typing never uses up a search.
            WhCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(shown(q.e164), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Button(onClick = { onAsk(q.e164) }, shape = RoundedCornerShape(8.dp)) { Text("Search") }
                }
            }
        } else item {
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
                        // Truecaller beside a searched number not in your contacts (24 Nov 2026), as on Calls.
                        trailing = if (!state.isSaved(r.number) && !r.takenOut) { { TruecallerPill(r.number) } } else null,
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
        is SearchQuery.Name -> {
            val matches = state.contacts.filter { c -> c.shownName?.let { SearchInput.matches(it, q.text) } == true && c.e164 != null }
                .distinctBy { it.e164 }
            item { SectionLabel("Your contacts") }
            if (matches.isEmpty()) item { HintBox(Icons.Filled.Search, "No contact called \"${q.text.trim()}\"", "Type a number to search everyone.") }
            items(matches, key = { "s:" + it.e164 }) { c -> ContactRow(state, c, mark = null, onNumber) }
        }
        SearchQuery.Incomplete -> item { HintBox(Icons.Filled.Search, "Type the number", "Like 98451 72046, 121 or +971 50 123 4567") }
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
internal fun AllowCard(onAllow: () -> Unit, onHowItWorks: () -> Unit) {
    WhCard(padding = 20.dp) {
        Text("See who has each number", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (BuildConfig.CALL_LOG) "Allow contacts and call log. Then you can:" else "Allow contacts. Then you can:",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TickLine("Find out who has your number")
        TickLine("See who has each number on your phone")
        TickLine("Know when something new happens or changes")
        if (BuildConfig.CALL_LOG) TickLine("After a call, get a notification if someone has the caller's number")
        // Google Play's prominent disclosure: what is sent and why, right before the Android prompts,
        // with a button that agrees to it (User Data policy).
        // Two short lines right above the button (Y, 23 Nov: no box).
        Text(DataNotice.text(BuildConfig.CALL_LOG), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
        PrimaryButton("Agree and allow", onAllow)
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

// ---------------------------------------------------------------- Updates: one list, latest change on top

/**
 * Every number in Updates (Y, 23 Nov 2026): the numbers from your contacts and calls that someone on
 * WhoHas has, each once, the one that changed most recently on top. A change is more people having it,
 * someone you know now having a caller's number, a contact joining, or a call from a number not in
 * your contacts (a call from one of your contacts is no news by itself). Your own number has its bar.
 */
internal fun updateItems(state: AppState): List<UpdateItem> {
    val mineRef = state.cards?.mine?.ref
    val log = state.changes
    fun e164Of(ref: String) = state.index.byRef[ref]?.e164
    val events = ArrayList<UpdateEvent>()
    for (ch in log.changes.values) {
        if (ch.ref == mineRef || ch.ref in state.blockedRefs) continue
        e164Of(ch.ref)?.let { events += UpdateEvent(it, ch.at, UpdateWhat.COUNT_UP) }
    }
    // A contact who joined is news on its own, even when nobody else has their number (24 Nov 2026:
    // the link-join test found such a join missing from Updates).
    val joinedNumbers = LinkedHashSet<String>()
    for ((ref, at) in log.joined) {
        if (ref in state.blockedRefs) continue
        e164Of(ref)?.let { events += UpdateEvent(it, at, UpdateWhat.JOINED); joinedNumbers += it }
    }
    for (gain in log.gains.values) {
        if (gain.ref == mineRef || gain.ref in state.blockedRefs) continue
        val e164 = e164Of(gain.ref) ?: continue
        if (state.isSaved(e164)) continue           // a contact's change is told by its count
        events += UpdateEvent(e164, gain.at, UpdateWhat.NOW_KNOWN)
    }
    fun shown(e164: String) = !state.isMine(e164) && state.cardFor(e164)?.let { it.total > 0 && !state.isBlocked(it) } == true
    val numbers = LinkedHashSet<String>()
    for (c in state.contacts) c.e164?.let { if (shown(it)) numbers += it }
    for (e164 in joinedNumbers) if (!state.isMine(e164) && state.cardFor(e164)?.let { state.isBlocked(it) } != true) numbers += e164
    for (call in state.calls) {
        val e164 = call.e164 ?: continue
        if (state.isSaved(e164) || !shown(e164)) continue
        numbers += e164
        events += UpdateEvent(e164, call.at, UpdateWhat.CALL)
    }
    // Numbers with nothing recent: people you know first, then the most people.
    val quiet = numbers.sortedWith(compareByDescending<String> { (state.cardFor(it)?.knownTotal ?: 0) > 0 }
        .thenByDescending { state.cardFor(it)?.total ?: 0 })
    return UpdatesOrder.order(quiet, events)
}

private fun LazyListScope.updatesTab(
    graph: AppGraph,
    state: AppState,
    all: List<UpdateItem>,
    onNumber: (String) -> Unit,
    onInvite: () -> Unit,
    onShareGroup: () -> Unit,
    onTurnOnNotifications: () -> Unit,
) {
    val mine = state.cards?.mine
    if (graph.store.levelReachedRecently(System.currentTimeMillis()) && (mine?.total ?: 0) >= app.foundwith.core.Levels.POPULAR_FROM) {
        item(key = "level-up") { LevelUpBanner(mine!!.total, onShareGroup) }
    }
    if (BuildConfig.CALL_LOG && graph.store.firstNamesSeen && !graph.store.notifAsked && !graph.notifier.canPost()) {
        item { NotificationsCard(onTurnOn = onTurnOnNotifications, onLater = { graph.store.notifAsked = true }) }
    }
    // People this phone doesn't know who joined with your link (23 Nov 2026): one note on top, with Share in group.
    val strangers = state.linkJoins().filter { it.e164 == null && (it.at ?: 0L) > state.dotsSince }
    if (strangers.isNotEmpty()) item(key = "link-joins") { LinkJoinNote(strangers, mine?.total ?: 0, onShareGroup) }
    val linkRefs = state.cards?.circle?.invite?.joinedRefs.orEmpty().toSet()
    val news = UpdatesOrder.newSince(all, state.dotsSince, state.seenNumbers)
    val newNumbers = news.mapTo(HashSet()) { it.e164 }
    val earlier = all.filter { it.e164 !in newNumbers }
    if (news.isNotEmpty()) {
        item(key = "h:new") { NewHeading(news.size) }
        itemsIndexed(news, key = { _, it -> "u:" + it.e164 }) { i, it ->
            InGroup(first = i == 0, last = i == news.lastIndex, tint = true) {
                // Someone who joined with your link: how far the next level is, and Share in group right under them.
                if (it.change?.what == UpdateWhat.JOINED && (state.cardFor(it.e164)?.ref ?: state.refFor(it.e164)) in linkRefs) {
                    Column {
                        UpdateRow(state, it, isNew = true, onNumber)
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinkJoins.levelLine(mine?.total ?: 0)?.let { line ->
                                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            PrimaryButton("Share in group", onShareGroup, icon = Icons.Filled.Share)
                        }
                    }
                } else {
                    UpdateRow(state, it, isNew = true, onNumber)
                }
            }
        }
    }
    if (earlier.isNotEmpty()) {
        if (news.isNotEmpty()) item(key = "h:earlier") { SectionLabel("Earlier") }
        itemsIndexed(earlier, key = { _, it -> "u:" + it.e164 }) { i, it ->
            InGroup(first = i == 0, last = i == earlier.lastIndex, tint = false) { UpdateRow(state, it, isNew = false, onNumber) }
        }
    }
    if (all.isEmpty()) item { NothingFoundCard(state) }
    if (graph.store.firstNamesSeen && state.profile?.name == null && !graph.store.nameAsked) item { NameCard(graph) }
}

/**
 * On top of Updates when people this phone doesn't know joined with your link (23 Nov 2026):
 * "Someone joined with your link", your count, how far the next level is, and Share in group.
 */
@Composable
internal fun LinkJoinNote(joins: List<LinkJoin>, total: Int, onShare: () -> Unit) {
    Surface(color = newRowTint(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconAvatar(Icons.Filled.Person, accent = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(LinkJoins.title(joins.map { it.name }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(HolderText.haveYours(total).replaceFirstChar { it.uppercase() } + " now",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                    LinkJoins.levelLine(total)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                joins.mapNotNull { it.at }.maxOrNull()?.let { at ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(whenText(at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp)); NewDot()
                    }
                }
            }
            PrimaryButton("Share in group", onShare, icon = Icons.Filled.Share)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One row of a section on the white page (Y, 23 Nov 2026): full width, a thin line under every row
 * but the last. New rows are light blue.
 */
@Composable
internal fun InGroup(first: Boolean, last: Boolean, tint: Boolean, content: @Composable () -> Unit) {
    Surface(
        color = if (tint) newRowTint() else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            content()
            if (!last) RowLine(onTint = tint)
        }
    }
}

/** The thin line between rows, starting where the names start. */
@Composable
private fun RowLine(onTint: Boolean = false) {
    HorizontalDivider(Modifier.padding(start = 68.dp),
        color = if (onTint) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.outlineVariant)
}

/** A tab's icon with its red count (Y, 23 Nov 2026), none at 0. */
@Composable
internal fun TabBadge(count: Int, icon: @Composable () -> Unit) {
    BadgedBox(badge = {
        if (count > 0) Badge(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) {
            Text(if (count > 99) "99+" else count.toString(), fontWeight = FontWeight.Bold)
        }
    }) { icon() }
}

/** "New" over the new rows, with how many in a red count (23 Nov 2026). */
@Composable
internal fun NewHeading(count: Int) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("New", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Badge(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) {
            Text(if (count > 99) "99+" else count.toString())
        }
    }
}

/** What changed for a number, in words, and which kind of change it was. */
internal data class ChangeLine(val text: String, val what: UpdateWhat)

/**
 * The words for a number's latest change (23 Nov 2026), when that change is why the number is on
 * top or is newer than [since]: "Joined WhoHas · has your number", "2 more people have it · now 14",
 * "Now known to Kiran Sir". Null when its latest news is only a call.
 */
internal fun AppState.changeLine(item: UpdateItem, since: Long): ChangeLine? {
    val change = item.change?.takeIf { it == item.latest || it.at > since } ?: return null
    val card = cardFor(item.e164)
    val ref = card?.ref ?: refFor(item.e164)
    val text = when (change.what) {
        UpdateWhat.COUNT_UP -> ref?.let { changes.changes[it] }?.let { HolderText.crossed(it.before, it.now) ?: HolderText.change(it.now, it.delta) }
        UpdateWhat.JOINED -> {
            val how = if (ref != null && ref in cards?.circle?.invite?.joinedRefs.orEmpty()) "Joined with your link" else "Joined WhoHas"
            if (cards?.mine?.known?.any { it.contactRef == ref } == true) "$how · has your number" else how
        }
        UpdateWhat.NOW_KNOWN -> ref?.let { changes.gains[it] }?.ids.orEmpty()
            .firstNotNullOfOrNull { id -> card?.known?.firstOrNull { it.id == id }?.let { personName(it) } }
            ?.let { "Now known to $it" }
        UpdateWhat.CALL -> null
    } ?: return null
    return ChangeLine(text, change.what)
}

/** Since when a number's change counts as new: when Updates was last looked at, or its card last opened. */
internal fun AppState.markSince(e164: String): Long = maxOf(dotsSince, seenNumbers[e164] ?: 0L)

/** The numbers marked new, by number: light blue with the dot in Updates, Call log and Contacts. */
internal fun AppState.marked(items: List<UpdateItem>): Map<String, UpdateItem> =
    UpdatesOrder.newSince(items, dotsSince, seenNumbers).associateBy { it.e164 }

/** Facebook's light blue for unseen rows, a shade lighter so green and red text stay readable (dark grey-blue in dark mode). */
@Composable
internal fun newRowTint(): androidx.compose.ui.graphics.Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) androidx.compose.ui.graphics.Color(0xFF1F2B3B)
    else androidx.compose.ui.graphics.Color(0xFFEDF5FF)

/** One number in Updates: who, from your contacts or your calls, what changed (or who has it), and when. */
@Composable
internal fun UpdateRow(state: AppState, item: UpdateItem, isNew: Boolean, onNumber: (String) -> Unit) {
    val e164 = item.e164
    val card = state.cardFor(e164)
    val ref = card?.ref
    val name = state.nameFor(e164, card)
    val saved = state.isSaved(e164)
    val call = if (saved) null else state.lastCall(e164)
    val at = item.at
    // What changed, when that is why the number is up here, or it is new since it was last seen.
    val change = state.changeLine(item, state.markSince(e164))
    val changeText = change?.text
    // When the latest change happened, on the right; under New in blue, with the dot.
    // Truecaller under it for numbers that aren't your contacts (Y, 24 Nov 2026).
    val time: (@Composable () -> Unit)? = if (at == null && saved) null else {
        {
            Column(horizontalAlignment = Alignment.End) {
                if (at != null) {
                    Text(whenText(at), style = MaterialTheme.typography.labelMedium,
                        color = if (isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isNew) { Spacer(Modifier.height(6.dp)); NewDot() }
                }
                if (!saved) { Spacer(Modifier.height(6.dp)); TruecallerPill(e164) }
            }
        }
    }
    InfoRow(
        leading = { if (call != null) CallKindIcon(call) else if (name.isNumber) IconAvatar(Icons.Filled.Phone) else Avatar(e164, name.text) },
        name = name,
        sub = if (saved) "In your contacts" else call?.let { callLine(it) },
        subColor = if (call != null) callKindColour(call) else MaterialTheme.colorScheme.onSurfaceVariant,
        line = changeText ?: if (saved) state.lineFor(card, onYourPhone = true) else state.callerLine(card, onYourPhone = false),
        lineColor = when {
            change?.what == UpdateWhat.JOINED -> MaterialTheme.colorScheme.tertiary
            changeText != null -> MaterialTheme.colorScheme.primary
            else -> knownColour(card)
        },
        trailing = time,
        onClick = { onNumber(e164) },
        e164 = e164,
        tag = rowTag(card?.total, card?.totalIsLowerBound == true),
    )
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

/** Blue when you know someone who has the number, grey when it's only a count. */
@Composable
private fun knownColour(card: Card?): androidx.compose.ui.graphics.Color =
    if ((card?.knownTotal ?: 0) > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

/** Your number before the person allows anything, or while names are being found: the count and your level, no link. */
@Composable
private fun HeroBefore(state: AppState, line: String?) {
    val total = state.cards?.mine?.total ?: 0
    val phone = state.profile?.phone?.let(::shown)
    if (total <= 0) EmptyHero(phone)
    else HeroCard(phone, total, countLabel(total), line, extra = { LevelBlock(total) })
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

private fun LazyListScope.callsTab(graph: AppGraph, state: AppState, marks: Map<String, UpdateItem>, onNumber: (String) -> Unit, onAllow: () -> Unit) {
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
    // A number marked new is marked on its latest call only.
    val markedCalls = HashMap<Long, UpdateItem>()
    val markedNumbers = HashSet<String>()
    for (call in state.calls.sortedByDescending { it.at }) {
        val mark = call.e164?.let { marks[it] } ?: continue
        if (markedNumbers.add(mark.e164)) markedCalls[call.id] = mark
    }
    state.calls.groupBy { dayHeading(it.at, now) }.forEach { (day, calls) ->
        item(key = "day:$day") { SectionLabel(day) }
        itemsIndexed(calls, key = { _, it -> "c:" + it.id }) { i, call ->
            Column {
                CallRow(state, call, markedCalls[call.id], onNumber)
                if (i < calls.lastIndex) RowLine()
            }
        }
    }
}

@Composable
internal fun CallRow(state: AppState, call: CallEvent, mark: UpdateItem?, onNumber: (String) -> Unit) {
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
                else -> "WhoHas can't check this number"
            },
        )
        return
    }
    val card = state.cardFor(e164)
    val blocked = state.refFor(e164)?.let { it in state.blockedRefs } == true
    // Marked new (23 Nov 2026): light blue, the dot, and what changed.
    val change = mark?.let { state.changeLine(it, state.markSince(e164)) }
    Marked(mark != null) {
        InfoRow(
            leading = { CallKindIcon(call) },
            name = state.nameFor(e164, card),
            sub = sub, subColor = callKindColour(call),
            line = when {
                blocked -> "Blocked"
                change != null -> change.text
                else -> state.callerLine(card, onYourPhone = state.isSaved(e164))
            },
            lineColor = when {
                blocked -> MaterialTheme.colorScheme.onSurfaceVariant
                change?.what == UpdateWhat.JOINED -> MaterialTheme.colorScheme.tertiary
                change != null -> MaterialTheme.colorScheme.primary
                else -> knownColour(card)
            },
            isNew = mark != null,
            // Truecaller on numbers that aren't your contacts (Y, 24 Nov 2026).
            trailing = if (!state.isSaved(e164) && !blocked) { { TruecallerPill(e164) } } else null,
            onClick = { onNumber(e164) },
            e164 = e164,
            tag = rowTag(state.cardFor(e164)?.total, state.cardFor(e164)?.totalIsLowerBound == true),
        )
    }
}

/** A row marked new: on light blue (the same as New in Updates). */
@Composable
private fun Marked(marked: Boolean, content: @Composable () -> Unit) {
    if (!marked) { content(); return }
    Surface(color = newRowTint(), contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) { content() }
}

// ---------------------------------------------------------------- Contacts: everyone in your phone, A to Z

private fun LazyListScope.contactsTab(graph: AppGraph, state: AppState, marks: Map<String, UpdateItem>, onNumber: (String) -> Unit, onAllow: () -> Unit) {
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
            itemsIndexed(people, key = { _, it -> "k:" + (it.e164 ?: ("raw:" + it.raw)) }) { i, c ->
                Column {
                    ContactRow(state, c, c.e164?.let { marks[it] }, onNumber)
                    if (i < people.lastIndex) RowLine()
                }
            }
        }
}

@Composable
internal fun ContactRow(state: AppState, contact: SavedNumber, mark: UpdateItem?, onNumber: (String) -> Unit) {
    val context = LocalContext.current
    val e164 = contact.e164
    if (e164 == null) {
        val name = contact.shownName?.takeIf { it.isNotBlank() } ?: contact.raw
        InfoRow(
            leading = { Avatar(contact.raw, name) },
            name = app.foundwith.core.ShownName(name, app.foundwith.core.NameSource.CONTACT),
            line = "WhoHas can't check this number",
        )
        return
    }
    val card = state.cardFor(e164)
    val name = state.nameFor(e164, card)
    val total = card?.total ?: 0
    // No Share or Tell pill on these rows (22 Nov 2026): tapping opens the card, which has "Tell … on WhatsApp".
    // Marked new (23 Nov 2026): light blue, the dot, and what changed.
    val change = mark?.let { state.changeLine(it, state.markSince(e164)) }
    Marked(mark != null) {
        InfoRow(
            leading = { Avatar(e164, name.text) },
            name = name,
            line = change?.text ?: state.lineFor(card, onYourPhone = true),
            lineColor = when {
                change?.what == UpdateWhat.JOINED -> MaterialTheme.colorScheme.tertiary
                change != null -> MaterialTheme.colorScheme.primary
                else -> knownColour(card)
            },
            isNew = mark != null,
            onClick = { onNumber(e164) },
            e164 = e164,
            tag = rowTag(state.cardFor(e164)?.total, state.cardFor(e164)?.totalIsLowerBound == true),
        )
    }
}
