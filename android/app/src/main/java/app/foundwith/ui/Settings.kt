package app.foundwith.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foundwith.AppGraph
import app.foundwith.BuildConfig
import app.foundwith.data.ApiException
import app.foundwith.data.MyData
import app.foundwith.data.OfflineException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackBar(onBack: () -> Unit, title: String? = null) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingRow(icon: ImageVector?, title: String, value: String? = null, valueOk: Boolean = false,
                       danger: Boolean = false, onClick: (() -> Unit)? = null) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        headlineContent = { Text(title, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
        trailingContent = value?.let {
            { Text(it, color = if (valueOk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun Page(onBack: () -> Unit, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        BackBar(onBack)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        content()
    }
}

/** Settings (design screen 73). A permission that's off shows "Off"; tapping it asks again. */
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onPage: (String) -> Unit, onInvite: () -> Unit) {
    val state by graph.model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        scope.launch { graph.model.reloadPhone(); app.foundwith.work.Scheduling.syncNow(context) }
    }
    fun permission(p: String) = if (graph.store.denials(p) >= 2) context.openAppSettings() else ask.launch(p)
    val contactsOn = remember(tick, state) { graph.book.hasContactsPermission() }
    val callsOn = remember(tick, state) { graph.book.hasCallLogPermission() }
    val notesOn = remember(tick, state) { graph.notifier.canPost() }

    Page(onBack, "Settings") {
        SettingRow(Icons.Filled.Person, "Contacts", if (contactsOn) "On" else "Off", contactsOn) {
            if (contactsOn) context.openAppSettings() else permission(Manifest.permission.READ_CONTACTS)
        }
        if (BuildConfig.CALL_LOG) {
            SettingRow(Icons.Filled.Call, "Call log", if (callsOn) "On" else "Off", callsOn) {
                if (callsOn) context.openAppSettings() else permission(Manifest.permission.READ_CALL_LOG)
            }
        }
        SettingRow(Icons.Filled.Notifications, "Notifications", if (notesOn) "On" else "Off", notesOn) { onPage("notifications") }
        SettingRow(Icons.Filled.AccountCircle, "Your name", state.profile?.name ?: "Not set") { onPage("name") }
        SettingRow(Icons.Filled.Share, "Invite friends", onClick = onInvite)
        SettingRow(Icons.Filled.Lock, "Your data") { onPage("data") }
        SettingRow(Icons.Filled.Info, "Help") { onPage("help") }
        Text("If you turn off Contacts or Call log in phone settings, WhoHas removes those numbers at the next sync.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp))
    }
}

/** Notifications (22 Nov 2026): five switches. The first two are about calls, so the contacts-only version hides them. */
@Composable
fun NotificationsScreen(graph: AppGraph, onBack: () -> Unit, onFix: () -> Unit) {
    val context = LocalContext.current
    var unknown by remember { mutableStateOf(graph.store.notifyUnknownCalls) }
    var other by remember { mutableStateOf(graph.store.notifyOtherCalls) }
    var changes by remember { mutableStateOf(graph.store.notifyChanges) }
    var joined by remember { mutableStateOf(graph.store.notifyJoined) }
    var level by remember { mutableStateOf(graph.store.notifyLevel) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) graph.store.addDenial(Manifest.permission.POST_NOTIFICATIONS)
    }
    Page(onBack, "Notifications") {
        if (!graph.notifier.canPost()) {
            Banner(Icons.Filled.Warning, "Notifications are off for WhoHas.", "Turn on", warning = true) {
                if (Build.VERSION.SDK_INT >= 33 && graph.store.denials(Manifest.permission.POST_NOTIFICATIONS) < 2) {
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else context.openNotificationSettings()
            }
        }
        if (BuildConfig.CALL_LOG) {
            ToggleRow("Calls from unknown numbers", "A missed call from a number not in your contacts, when someone has it.", unknown) {
                unknown = it; graph.store.notifyUnknownCalls = it
            }
            ToggleRow("Other calls", "Received and dialled calls, quietly, when something is new.", other) {
                other = it; graph.store.notifyOtherCalls = it
            }
        }
        ToggleRow("Changes to your numbers", "Someone new has your number, or a count went up.", changes) {
            changes = it; graph.store.notifyChanges = it
        }
        ToggleRow("Friends who join", "Someone in your contacts joins WhoHas.", joined) {
            joined = it; graph.store.notifyJoined = it
        }
        ToggleRow("Your level", "When you reach Popular or Highly popular, or are close.", level) {
            level = it; graph.store.notifyLevel = it
        }
        Text("No sound from 9 pm to 9 am.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (BuildConfig.CALL_LOG) {
            TextButton(onClick = onFix, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Not getting notifications after calls?") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Your name (75, 76). Choosing it the first time is free; after a change, the next one waits 14 days. */
@Composable
fun NameScreen(graph: AppGraph, onBack: () -> Unit) {
    val state by graph.model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(state.profile?.name.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    Page(onBack, "Your name on WhoHas") {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("People who don't have you in their contacts see this name.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = name, onValueChange = { name = it.take(40); saved = false; error = null }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = error != null)
            ErrorText(error)
            if (saved) Text("Name updated", color = MaterialTheme.colorScheme.tertiary)
            PrimaryButton("Save", {
                scope.launch {
                    error = graph.model.setName(name)
                    saved = error == null
                }
            }, enabled = name.isNotBlank() && name.trim() != state.profile?.name)
        }
    }
}

/** Your data and account (77): what is stored, in plain words, and the ways out. */
@Composable
fun DataScreen(graph: AppGraph, onBack: () -> Unit, onBlocked: () -> Unit, onSignedOut: () -> Unit, onDeleted: () -> Unit) {
    val state by graph.model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<MyData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        data = try {
            graph.api.myData()
        } catch (e: ApiException) {
            error = e.message; null
        } catch (e: OfflineException) {
            error = e.message; null
        }
    }
    Page(onBack, "Your data") {
        Text("Stored safely on WhoHas", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        SettingRow(null, "Your number", state.profile?.phone?.let { shown(it) } ?: "")
        SettingRow(null, "Your name", state.profile?.name ?: "Not set")
        SettingRow(null, "Contacts", data?.numbers?.contacts?.toString() ?: "…")
        if (BuildConfig.CALL_LOG) SettingRow(null, "Call log numbers", data?.numbers?.callLog?.toString() ?: "…")
        ErrorText(error, Modifier.padding(horizontal = 20.dp))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingRow(Icons.Filled.Star, "Blocked people", onClick = onBlocked)
        SettingRow(Icons.Filled.Share, "Download my data") {
            if (busy) return@SettingRow
            busy = true
            scope.launch {
                try {
                    val text = graph.api.export()
                    val file = withContext(Dispatchers.IO) {
                        File(context.cacheDir, "exports").apply { mkdirs() }.resolve("found-with-data.json").apply { writeText(text) }
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Your WhoHas data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ApiException) {
                    error = e.message
                } catch (e: OfflineException) {
                    error = e.message
                } finally {
                    busy = false
                }
            }
        }
        SettingRow(Icons.AutoMirrored.Filled.ExitToApp, "Sign out") { confirm = "signout" }
        SettingRow(null, "Delete my account", danger = true) { confirm = "delete" }
    }
    when (confirm) {
        "signout" -> ConfirmDialog("Sign out?", "Your account stays. Sign in again any time with your number.", "Sign out",
            onConfirm = {
                confirm = null
                android.util.Log.i("FoundWith", "sign out confirmed")
                scope.launch {
                    graph.forgetEverything()
                    android.util.Log.i("FoundWith", "signed out")
                    // Navigation must happen on the main thread, whatever thread sign-out ended on.
                    withContext(Dispatchers.Main) { onSignedOut() }
                }
            }, onDismiss = { confirm = null })
        "delete" -> ConfirmDialog("Delete your account?", app.foundwith.core.AccountText.deleteDialog(BuildConfig.CALL_LOG),
            "Delete my account", danger = true, cancel = "Keep my account",
            onConfirm = {
                confirm = null
                scope.launch {
                    try {
                        graph.api.deleteAccount()
                        graph.forgetEverything()
                        graph.justDeleted = true
                        withContext(Dispatchers.Main) { onDeleted() }
                    } catch (e: ApiException) {
                        error = e.message
                    } catch (e: OfflineException) {
                        error = e.message
                    }
                }
            }, onDismiss = { confirm = null })
    }
}

/** Blocked people (78). */
@Composable
fun BlockedScreen(graph: AppGraph, onBack: () -> Unit) {
    val state by graph.model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Page(onBack, "Blocked people") {
        if (state.blocks.isEmpty()) {
            Text("You haven't blocked anyone.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
        for (block in state.blocks) {
            val e164 = block.ref?.let { state.index.byRef[it]?.e164 }
            val name = e164?.let { state.contactName(it) } ?: block.name ?: "Someone on WhoHas"
            ListItem(
                leadingContent = { Avatar(block.id.toString(), name) },
                headlineContent = { Text(name) },
                trailingContent = {
                    OutlinedButton(onClick = { scope.launch { error = graph.model.unblockId(block.id) } }) { Text("Unblock") }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        ErrorText(error, Modifier.padding(horizontal = 20.dp))
    }
}

/** Help (80): How it works, contact, the policies. Play needs the privacy policy reachable in the app. */
@Composable
fun HelpScreen(onBack: () -> Unit, onHowItWorks: () -> Unit) {
    val context = LocalContext.current
    Page(onBack, "Help") {
        SettingRow(Icons.Filled.Info, "How WhoHas works", onClick = onHowItWorks)
        SettingRow(Icons.Filled.Email, "Write to us", HELP_EMAIL) { context.email(HELP_EMAIL) }
        if (DELETE_URL.isNotBlank()) SettingRow(Icons.Filled.Lock, "Delete my account without the app") { context.openUrl(DELETE_URL) }
        if (PRIVACY_URL.isNotBlank()) SettingRow(Icons.Filled.Lock, "Privacy policy") { context.openUrl(PRIVACY_URL) }
        if (TERMS_URL.isNotBlank()) SettingRow(Icons.Filled.Lock, "Terms of use") { context.openUrl(TERMS_URL) }
        if (GRIEVANCE_URL.isNotBlank()) SettingRow(Icons.Filled.Lock, "Grievance officer") { context.openUrl(GRIEVANCE_URL) }
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
    }
}

/** How WhoHas works: what it uses, what you see, what others see, shared names, safety. */
@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
    Page(onBack, "How WhoHas works") {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Explain(if (BuildConfig.CALL_LOG) "1. You allow contacts and call log." else "1. You allow contacts.",
                "WhoHas checks those numbers against other users.")
            Explain("2. You see who has any number.", "People you know are shown by name. Others are a count.")
            Explain("3. Others see you the same way.", "If someone who knows you looks up a number you have, they see your name.")
            Explain("Names people know a number by", "If 3 or more people have the same name for a number in their contacts, that name can show for it.")
            Explain("Kept safe", "We never message or call anyone. Everything is stored safely, and you can delete it any time in Settings › Your data.")
        }
    }
}

@Composable
private fun Explain(title: String, text: String) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shown instead of the app until it's updated (69). */
@Composable
fun UpdateNeededScreen() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        EmptyState(Icons.Filled.Warning, "Update WhoHas", "This version is too old. Update from the Play Store.", "Update") {
            context.openUrl("https://play.google.com/store/apps/details?id=${context.packageName}")
        }
    }
}

/** Shown instead of the app for an account stopped for misuse (70). */
@Composable
fun SuspendedScreen(graph: AppGraph, onSignedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        EmptyState(Icons.Filled.Warning, "Your account is suspended",
            "It broke WhoHas rules. If this is a mistake, write to $HELP_EMAIL.")
        OutlinedButton(onClick = { scope.launch { graph.forgetEverything(); onSignedOut() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
        Spacer(Modifier.width(0.dp))
    }
}
