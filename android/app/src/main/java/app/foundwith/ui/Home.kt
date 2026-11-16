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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foundwith.AppGraph
import app.foundwith.BuildConfig
import app.foundwith.core.CallEvent
import app.foundwith.core.Changes
import app.foundwith.core.HolderText
import app.foundwith.core.Messages
import app.foundwith.core.NameSource
import app.foundwith.core.PhoneNumbers
import app.foundwith.core.SearchInput
import app.foundwith.core.SearchQuery
import app.foundwith.core.ShownName
import app.foundwith.core.UpdatesFeed
import app.foundwith.data.ApiException
import app.foundwith.data.AppState
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
 * Home: "Whose number is this?" at the top, then Updates, Calls and Contacts as tabs at the
 * bottom, within thumb's reach on tall phones. Before the person allows anything, Home shows their
 * own number's count and the Allow card instead.
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

    val allowPermissions = remember {
        buildList {
            add(Manifest.permission.READ_CONTACTS)
            if (BuildConfig.CALL_LOG) add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
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
            Found.Problem("No internet", "Search needs the internet. Home still shows your last update.")
        } catch (e: ApiException) {
            if (e.code == "quota_exceeded") Found.Problem("Today's search limit is over", "Search again tomorrow. Calls and contacts keep updating.")
            else Found.Problem("Couldn't check right now", e.message)
        }
    }

    Scaffold(
        bottomBar = {
            if (state.setup == Setup.READY && query.isBlank()) {
                NavigationBar {
                    val newCount = updatesCount(state)
                    NavigationBarItem(selected = tab == Tab.UPDATES, onClick = { tab = Tab.UPDATES }, label = { Text("Updates") },
                        icon = {
                            BadgedBox(badge = { if (newCount > 0) Badge { Text(newCount.toString()) } }) {
                                Icon(Icons.Filled.Notifications, contentDescription = null)
                            }
                        })
                    if (BuildConfig.CALL_LOG) {
                        NavigationBarItem(selected = tab == Tab.CALLS, onClick = { tab = Tab.CALLS }, label = { Text("Calls") },
                            icon = { Icon(Icons.Filled.Call, contentDescription = null) })
                    }
                    NavigationBarItem(selected = tab == Tab.CONTACTS, onClick = { tab = Tab.CONTACTS }, label = { Text("Contacts") },
                        icon = { Icon(Icons.Filled.Person, contentDescription = null) })
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp,
            top = padding.calculateTopPadding())) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Found With", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                }
            }
            item { SearchBox(query, onChange = { query = it }) }

            if (query.isNotBlank()) {
                searchResults(state, query, found, onNumber)
                return@LazyColumn
            }

            item { Banners(graph, state, onFix, onTurnOnNotifications = {
                if (Build.VERSION.SDK_INT >= 33 && graph.store.denials(Manifest.permission.POST_NOTIFICATIONS) < 2) {
                    ask(Manifest.permission.POST_NOTIFICATIONS)
                } else context.openNotificationSettings()
            }) }

            when (state.setup) {
                Setup.NOT_ALLOWED -> item {
                    RewardCard(state, onAllow = {
                        graph.store.allowTapped = true
                        if (allowPermissions.all { graph.store.denials(it) >= 2 }) context.openAppSettings() else askAll.launch(allowPermissions)
                    }, onHowItWorks = onHowItWorks)
                }
                Setup.FINDING -> item { FindingCard(state, failed = false) {} }
                Setup.FIND_FAILED -> item { FindingCard(state, failed = true) { scope.launch { graph.model.retryFinding() } } }
                Setup.READY -> when (tab) {
                    Tab.UPDATES -> updatesTab(graph, state, onNumber, onInvite)
                    Tab.CALLS -> callsTab(graph, state, onNumber, onAllow = { ask(Manifest.permission.READ_CALL_LOG) })
                    Tab.CONTACTS -> contactsTab(graph, state, onNumber, onAllow = { ask(Manifest.permission.READ_CONTACTS) })
                }
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Whose number is this?") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun LazyListScope.searchResults(state: AppState, query: String, found: Found, onNumber: (String) -> Unit) {
    when (val q = SearchInput.classify(query)) {
        is SearchQuery.Name -> {
            val matches = state.contacts.filter { c -> c.shownName?.let { SearchInput.matches(it, q.text) } == true && c.e164 != null }
                .distinctBy { it.e164 }
            item { SectionLabel("From your phone") }
            if (matches.isEmpty()) item { HintBox(Icons.Filled.Search, "No one called that", "Letters search your own contacts. Type a number to search everyone.") }
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
                        line = if (r.takenOut) "Not shown on Found With" else state.lineFor(card, onYourPhone = state.isSaved(r.number)),
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
        SearchQuery.Incomplete -> item { HintBox(Icons.Filled.Search, "Type the full number", "Like 98451 72046") }
        SearchQuery.NotMobile -> item { HintBox(Icons.Filled.Search, "Type a 10-digit mobile number", "Search works with mobile numbers. Like 98451 72046") }
        SearchQuery.Empty -> Unit
    }
}

@Composable
private fun HintBox(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(12.dp)) {
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
        state.offline -> Banner(Icons.Filled.Warning, "Offline · last updated ${state.cards?.savedAt?.let { whenText(it, now) } ?: "never"}")
        state.setup == Setup.READY && BuildConfig.CALL_LOG && graph.book.hasCallLogPermission() && graph.store.afterCalls &&
            !graph.notifier.canPost() ->
            Banner(Icons.Filled.Notifications, "Notifications are off. You won't hear after calls.", "Turn on", warning = true, onAction = onTurnOnNotifications)
        state.lateCalls >= 2 && now - graph.store.fixDismissedAt > Changes.KEEP_MS ->
            Banner(Icons.Filled.Warning, "Found With couldn't check your last ${state.lateCalls} callers.", "Fix", warning = true, onAction = onFix)
    }
}

/** First Home: the reward ("Found with 23 people") and the Allow card, together. */
@Composable
private fun RewardCard(state: AppState, onAllow: () -> Unit, onHowItWorks: () -> Unit) {
    val total = state.cards?.mine?.total ?: 0
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your number", style = MaterialTheme.typography.labelLarge)
            Text(if (total > 0) "Found with ${HolderText.count(total)}" else HolderText.NO_ONE,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (total > 0) "${HolderText.count(total)} ${if (total == 1) "has" else "have"} your number" else "No one here has your number yet",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(if (total > 0) "See which of them you know" else "Find who you know here", fontWeight = FontWeight.SemiBold)
            Text(buildString {
                append("Found With uses the numbers on your phone to find them.")
                if (BuildConfig.CALL_LOG) append(" After a call, it also tells you who knows the caller.")
            }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Allow", onAllow)
            TextButton(onClick = onHowItWorks, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stored encrypted · How it works")
            }
        }
    }
}

@Composable
private fun FindingCard(state: AppState, failed: Boolean, onTryNow: () -> Unit) {
    val total = state.cards?.mine?.total ?: 0
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your number", style = MaterialTheme.typography.labelLarge)
            Text(if (total > 0) "Found with ${HolderText.count(total)}" else HolderText.NO_ONE,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (failed) {
                Text("Couldn't finish. Trying again by itself.")
                PrimaryButton("Try now", onTryNow)
            } else {
                Text("Finding who you know…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------- Updates

/** Updates: your number on top, then calls from numbers you haven't saved and contacts whose count went up. */
private data class UpdateEntry(val key: String, val e164: String, val at: Long, val isCall: Boolean, val call: CallEvent? = null)

private fun updateEntries(state: AppState): List<UpdateEntry> {
    val now = System.currentTimeMillis()
    val weekAgo = now - Changes.KEEP_MS
    val calls = state.calls.asSequence()
        .filter { it.isIncoming && !it.isHidden && it.at >= weekAgo }
        .filter { c -> val e = c.e164!!; !state.isSaved(e) && !state.isMine(e) && state.refFor(e)?.let { it in state.blockedRefs } != true }
        .distinctBy { it.e164 }
        .map { UpdateEntry("call:${it.e164}", it.e164!!, it.at, true, it) }
        .toList()
    val mineRef = state.cards?.mine?.ref
    val changes = state.changes.changes.values
        .filter { it.ref != mineRef && it.ref !in state.blockedRefs }
        .mapNotNull { ch -> state.index.byRef[ch.ref]?.e164?.let { UpdateEntry("change:${ch.ref}", it, ch.at, false) } }
    return calls + changes
}

private fun updatesCount(state: AppState): Int = updateEntries(state).count { it.at > state.dotsSince }

private fun LazyListScope.updatesTab(graph: AppGraph, state: AppState, onNumber: (String) -> Unit, onInvite: () -> Unit) {
    val now = System.currentTimeMillis()
    val mine = state.cards?.mine
    item {
        val change = mine?.let { state.changes.changes[it.ref] }
        val todayLine = change?.takeIf { Changes.isToday(it, now, startOfToday(now)) }?.let { "${HolderText.more(it.delta)} today" }
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { state.profile?.phone?.let(onNumber) }) {
            NumberRow(leading = { MeAvatar() }, name = ShownName("Your number", NameSource.CONTACT),
                line = state.lineFor(mine, onYourPhone = false), sub = todayLine)
        }
    }
    if (state.profile?.name == null && !graph.store.nameAsked) item { NameRow(graph) }

    val entries = updateEntries(state)
    if (state.cards?.cards.isNullOrEmpty() && (mine?.total ?: 0) == 0 && entries.isEmpty()) {
        item {
            EmptyState(Icons.Filled.Person, "None of your contacts are here yet",
                "When a friend joins, you see if they have your number.", button = "Invite friends", onButton = onInvite)
        }
        return
    }
    if (entries.isEmpty()) {
        item { EmptyState(Icons.Filled.CheckCircle, "You're all caught up", "New calls and changes show here.") }
        return
    }
    val rows = UpdatesFeed.rows(entries.filter { it.isCall }.map { it.key to it.at }, entries.filter { !it.isCall }.map { it.key to it.at },
        state.dotsSince, startOfToday(now))
    val byKey = entries.associateBy { it.key }
    val firstWeek = graph.store.lastOpenedAt == 0L
    val today = rows.filter { it.isToday }
    val earlier = rows.filter { !it.isToday }
    if (firstWeek && rows.isNotEmpty() && entries.all { it.isCall }) item { SectionLabel("Calls this week from numbers you haven't saved") }
    else if (today.isNotEmpty()) item { SectionLabel("Today") }
    items(today, key = { it.key }) { row -> UpdateRowView(state, byKey.getValue(row.key), row.isNew, onNumber) }
    if (earlier.isNotEmpty()) {
        if (!firstWeek) item { SectionLabel("Earlier") }
        items(earlier, key = { it.key }) { row -> UpdateRowView(state, byKey.getValue(row.key), row.isNew, onNumber) }
    }
}

@Composable
private fun UpdateRowView(state: AppState, entry: UpdateEntry, isNew: Boolean, onNumber: (String) -> Unit) {
    val card = state.cardFor(entry.e164)
    val name = state.nameFor(entry.e164, card)
    val line = if (entry.isCall) state.lineFor(card, onYourPhone = false)
    else state.changes.changes[entry.key.removePrefix("change:")]?.let { HolderText.change(it.now, it.delta) } ?: state.lineFor(card, true)
    NumberRow(
        leading = { if (entry.isCall && name.isNumber) IconAvatar(Icons.Filled.Phone) else Avatar(entry.e164, name.text) },
        name = name,
        line = line,
        sub = entry.call?.let { callLine(it) },
        trailing = if (isNew) ({ NewDot() }) else null,
        onClick = { onNumber(entry.e164) },
    )
}

/** Shown once: "Your name on Found With", filled in from the phone when it can be. */
@Composable
private fun NameRow(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed) return
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your name on Found With", fontWeight = FontWeight.SemiBold)
            Text("People who haven't saved your number see this name.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    placeholder = { Text("Your name") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = name.isNotBlank() && !saving, onClick = {
                    saving = true
                    scope.launch { error = graph.model.setName(name); saving = false }
                }) { Text("Save") }
            }
            ErrorText(error)
            TextButton(onClick = { graph.store.nameAsked = true; dismissed = true }) { Text("Not now") }
        }
    }
}

// ---------------------------------------------------------------- Calls

private fun LazyListScope.callsTab(graph: AppGraph, state: AppState, onNumber: (String) -> Unit, onAllow: () -> Unit) {
    if (!graph.book.hasCallLogPermission()) {
        item {
            if (graph.store.denials(Manifest.permission.READ_CALL_LOG) >= 2) {
                EmptyState(Icons.Filled.Lock, "Call log is off", "In Settings, tap Permissions, then Call logs, then Allow.", "Open settings", onAllow)
            } else {
                EmptyState(Icons.Filled.Call, "See who knows your callers", "Allow the call log, and every recent call shows who knows the number.",
                    "Allow call log", onAllow)
            }
        }
        return
    }
    if (state.calls.isEmpty()) {
        item { EmptyState(Icons.Filled.Call, "No calls yet", "Calls show here with who knows the number.") }
        return
    }
    items(state.calls, key = { "c:" + it.id }) { call -> CallRow(state, call, onNumber) }
}

@Composable
private fun CallRow(state: AppState, call: CallEvent, onNumber: (String) -> Unit) {
    val e164 = call.e164
    if (e164 == null || call.isHidden) {
        NumberRow(leading = { IconAvatar(Icons.Filled.Lock) }, name = ShownName("Private number", NameSource.NUMBER),
            line = "Hidden numbers can't be checked", trailing = { TimeText(call.at) })
        return
    }
    val card = state.cardFor(e164)
    val name = state.nameFor(e164, card)
    val blocked = state.refFor(e164)?.let { it in state.blockedRefs } == true
    NumberRow(
        leading = { if (name.isNumber) IconAvatar(Icons.Filled.Phone) else Avatar(e164, name.text) },
        name = name,
        line = if (blocked) "Blocked" else state.lineFor(card, onYourPhone = state.isSaved(e164)),
        sub = callLine(call).substringBefore(" ·"),
        trailing = { TimeText(call.at) },
        onClick = { onNumber(e164) },
    )
}

@Composable
private fun TimeText(at: Long) {
    Text(whenText(at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------------------------------------------------------- Contacts

private fun LazyListScope.contactsTab(graph: AppGraph, state: AppState, onNumber: (String) -> Unit, onAllow: () -> Unit) {
    if (!graph.book.hasContactsPermission()) {
        item {
            if (graph.store.denials(Manifest.permission.READ_CONTACTS) >= 2) {
                EmptyState(Icons.Filled.Lock, "Contacts are off", "In Settings, tap Permissions, then Contacts, then Allow.", "Open settings", onAllow)
            } else {
                EmptyState(Icons.Filled.Person, "See who knows your contacts", "Allow contacts, and each one shows who else has the number.",
                    "Allow contacts", onAllow)
            }
        }
        return
    }
    val all = state.contacts.filter { it.e164 != null && !state.isMine(it.e164!!) }.distinctBy { it.e164 }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shownName ?: it.raw })
    if (all.isEmpty()) {
        item { EmptyState(Icons.Filled.Person, "No contacts on this phone", "Contacts you save show here with who else has the number.") }
        return
    }
    val (found, onlyYou) = all.partition { (state.cardFor(it.e164!!)?.total ?: 0) > 0 }
    items(found, key = { "k:" + it.e164 }) { c -> ContactRow(state, c, onNumber) }
    if (onlyYou.isNotEmpty()) {
        item { SectionLabel("Only you have these") }
        items(onlyYou, key = { "o:" + it.e164 }) { c -> ContactRow(state, c, onNumber, offerInvite = true) }
    }
}

@Composable
private fun ContactRow(state: AppState, contact: SavedNumber, onNumber: (String) -> Unit, offerInvite: Boolean = false) {
    val context = LocalContext.current
    val e164 = contact.e164 ?: return
    val card = state.cardFor(e164)
    val name = state.nameFor(e164, card)
    val member = card?.onFoundWith == true || state.refFor(e164)?.let { it in state.cards?.circle?.memberRefs.orEmpty() } == true
    val canInvite = offerInvite && PhoneNumbers.isIndianMobile(e164) && !member
    NumberRow(
        leading = { Avatar(e164, name.text) },
        name = name,
        line = state.lineFor(card, onYourPhone = true),
        trailing = if (canInvite) ({
            OutlinedButton(onClick = {
                val link = state.cards?.circle?.invite?.link ?: app.foundwith.data.Invites.PLAY_STORE
                context.messagePerson(e164, Messages.invite(card?.total ?: 0, link))
            }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Invite") }
        }) else null,
        onClick = { onNumber(e164) },
    )
}
