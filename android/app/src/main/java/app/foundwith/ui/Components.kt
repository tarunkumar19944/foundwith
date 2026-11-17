package app.foundwith.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.foundwith.BuildConfig
import app.foundwith.core.HolderText
import app.foundwith.core.ShownName

/** A ViewModel built from the app's objects; survives rotation like any other. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// ---------------------------------------------------------------- talking to other apps

/** Opens Android's share sheet with [text]. */
fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Opens a chat with one person, [text] typed in, for them to send: WhatsApp when it's installed
 * (also WhatsApp Business), else Messages, else the share sheet. Nothing is sent by itself.
 */
fun Context.messagePerson(e164: String?, text: String) {
    if (e164 == null) return shareText(text)
    val digits = e164.trimStart('+')
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        val chat = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}"))
            .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(chat)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$e164")).putExtra("sms_body", text)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(sms)
    } catch (e: ActivityNotFoundException) {
        shareText(text)
    }
}

/**
 * Opens the share sheet with a picture from the app ([resId]) and [text], for a WhatsApp group.
 * The picture is copied to the cache and handed over through the app's FileProvider.
 */
fun Context.shareImage(resId: Int, text: String, title: String) {
    val file = runCatching {
        val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
        java.io.File(dir, "whohas.png").also { out ->
            resources.openRawResource(resId).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }.getOrNull() ?: return shareText(text)
    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(null, uri)
    runCatching {
        startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { shareText(text) }
}

/** "Call back": the phone's own dialer, with the number filled in. No call permission needed. */
fun Context.dial(e164: String) {
    runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$e164")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** "Save to contacts": the phone's own new-contact screen with the number filled in. */
fun Context.saveContact(e164: String) {
    val insert = Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.PHONE, e164)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(insert) }
}

/** The app's own page in Android settings: permissions, notifications, battery. Every phone has it. */
fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        openAppSettings()
    }
}

fun Context.openUrl(url: String) {
    if (url.isBlank()) return
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun Context.email(address: String) {
    runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

const val HELP_EMAIL = "help@found-with.app"
val PRIVACY_URL: String get() = BuildConfig.PRIVACY_URL

// ---------------------------------------------------------------- building blocks

private val AvatarColours = listOf(
    Color(0xFFDCE9FF) to Color(0xFF1C4787),
    Color(0xFFD7F1E0) to Color(0xFF16613A),
    Color(0xFFFFE3CF) to Color(0xFF87390C),
    Color(0xFFECE0FA) to Color(0xFF592A88),
    Color(0xFFFCDFE5) to Color(0xFF8A1E39),
)

/** A circle with initials. The letters keep their size whatever the phone's font setting, so they always fit. */
@Composable
fun Avatar(seed: String, name: String, size: Dp = 40.dp) {
    val (bg, fg) = AvatarColours[Math.floorMod(seed.hashCode(), AvatarColours.size)]
    val letters = with(LocalDensity.current) { (size * 0.38f).toSp() }
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(HolderText.initials(name), color = fg, fontSize = letters, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A plain circle with an icon, for calls without a name and for your own number. */
@Composable
fun IconAvatar(icon: ImageVector, size: Dp = 40.dp, accent: Boolean = false) {
    val bg = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg)
    }
}

@Composable
fun MeAvatar(size: Dp = 40.dp) = IconAvatar(Icons.Filled.Person, size, accent = true)

/** A name with its small grey tag ("their own name", "what others call it") when it isn't the viewer's own. */
@Composable
fun NameText(name: ShownName, big: Boolean = false, maxLines: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name.text,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        name.tag?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * One row in every list: who, the one line, and an optional small line under it. The line wraps
 * to two lines rather than cutting off the count.
 */
@Composable
fun NumberRow(
    leading: @Composable () -> Unit,
    name: ShownName,
    line: String,
    sub: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = leading,
        headlineContent = { NameText(name) },
        supportingContent = {
            Column {
                Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The green dot for what's new since the app was last open. */
@Composable
fun NewDot() {
    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    saffron: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = when {
        danger -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        saffron -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
        else -> ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        colors = colors,
        shape = RoundedCornerShape(26.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A one-line strip above the tabs: offline, notifications off, phone settings. */
@Composable
fun Banner(icon: ImageVector, text: String, action: String? = null, warning: Boolean = false, onAction: () -> Unit = {}) {
    val bg = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action) }
        }
    }
}

/** An empty tab or a locked one: an icon, a line, a sentence, maybe a button. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, button: String? = null, onButton: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (button != null) {
            Spacer(Modifier.height(8.dp))
            PrimaryButton(button, onButton)
        }
    }
}

/** "Block Vikram?" and the like: a short question with Cancel and one action. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- WhoHas pieces

/** "WhoHas", with "Has" in indigo. */
@Composable
fun Wordmark(size: TextUnit = 22.sp) {
    Text(
        buildAnnotatedString {
            append("Who")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Has") }
        },
        fontSize = size, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp,
    )
}

/** "just now", "5 min ago", "2 hr ago", else the time or day. */
fun agoText(at: Long?, now: Long = System.currentTimeMillis()): String {
    if (at == null) return "just now"
    val gap = now - at
    return when {
        gap < 60_000 -> "just now"
        gap < 3_600_000 -> "${gap / 60_000} min ago"
        gap < 6 * 3_600_000 -> "${gap / 3_600_000} hr ago"
        else -> whenText(at, now)
    }
}

/** "Live · checked just now" with a slow green pulse, or what's shown while offline. */
@Composable
fun LiveStatus(checkedAt: Long?, offline: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(checkedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    if (offline) {
        Text("No internet · showing what we had at ${checkedAt?.let { whenText(it, now) } ?: "last open"}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val pulse = rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "live-dot")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = alpha), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("Live · checked ${agoText(checkedAt, now)}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
    }
}

/** A card on Home: white, a hairline edge, rounded. */
@Composable
fun WhCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    edge: Boolean = true,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = color, shape = RoundedCornerShape(20.dp),
        border = if (edge) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** A small rounded label: saffron for what's new ("+5 this week"), indigo tint otherwise. */
@Composable
fun Chip(text: String, icon: ImageVector? = null, saffron: Boolean = true, strong: Boolean = false) {
    val bg = when {
        strong -> MaterialTheme.colorScheme.secondary
        saffron -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val fg = when {
        strong -> MaterialTheme.colorScheme.onSecondary
        saffron -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** "Tell": saffron, at least 44 dp tall. After a tap it reads "Told" and does nothing. */
@Composable
fun TellButton(told: Boolean, name: String, onClick: () -> Unit) {
    if (told) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Told", fontWeight = FontWeight.Bold)
            }
        }
        return
    }
    Button(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary),
    ) { Text("Tell", fontWeight = FontWeight.Bold, modifier = Modifier) }
}

/** Overlapping avatars for a few people. [people] are (seed, name). */
@Composable
fun PeopleStack(people: List<Pair<String, String>>, ring: Color, size: Dp = 30.dp) {
    Box {
        people.take(4).forEachIndexed { i, (seed, name) ->
            Box(Modifier.padding(start = (size.value * 0.66f * i).dp).border(2.dp, ring, CircleShape)) { Avatar(seed, name, size) }
        }
    }
}

/** A line with a green tick, for "WhoHas will:" lists. */
@Composable
fun TickLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp).size(22.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The big indigo card for your own number. */
@Composable
fun HeroCard(
    number: String?,
    count: Int,
    countLabel: String,
    line: String?,
    people: List<Pair<String, String>> = emptyList(),
    chip: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val onHero = MaterialTheme.colorScheme.onPrimary
    Surface(
        color = MaterialTheme.colorScheme.primary, contentColor = onHero, shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("Your number", style = MaterialTheme.typography.labelLarge, color = onHero.copy(alpha = 0.88f), modifier = Modifier.weight(1f))
                if (number != null) Text(number, style = MaterialTheme.typography.labelLarge, color = onHero.copy(alpha = 0.88f))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(HolderText.grouped(count), fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 52.sp)
                Spacer(Modifier.width(10.dp))
                Text(countLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (line != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (people.isNotEmpty()) {
                        PeopleStack(people, ring = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(line, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            if (chip != null) Chip(chip, Icons.Filled.KeyboardArrowUp, strong = true)
        }
    }
}
