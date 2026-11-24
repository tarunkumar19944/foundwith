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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.Search
import app.foundwith.core.HolderText
import app.foundwith.core.Levels
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

/** Copies the plain ten digits. Android 13 and later show their own "Copied" box; older phones get a short toast. */
fun Context.copyNumber(e164: String) {
    val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Number", app.foundwith.core.PhoneNumbers.copyText(e164)))
    if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, "Number copied", android.widget.Toast.LENGTH_SHORT).show()
}

/** How the Truecaller button reached Truecaller. Test builds show it in a short note. */
enum class TruecallerRoute(val note: String) {
    SEARCH("Opened Truecaller search"),
    WEB_LINK("Opened Truecaller's page for the number"),
    DIAL_PAD("Opened Truecaller's dial pad"),
    APP("Opened Truecaller (number copied)"),
    WEBSITE("Opened truecaller.com search"),
    NONE("Couldn't open Truecaller"),
}

/**
 * Opens Truecaller on the number (Y, 23 Nov 2026: it should search straight away). Truecaller has
 * no published link that takes a number, so this tries, in order, what an installed Truecaller says
 * it accepts: its "search with Truecaller" text action, its own number page link, its dial pad with
 * the number typed, then Truecaller itself. Without Truecaller: its website search. The number is
 * copied first, so it can always be pasted.
 */
fun Context.searchInTruecaller(e164: String): TruecallerRoute {
    copyNumber(e164)
    val digits = app.foundwith.core.PhoneNumbers.copyText(e164)
    val truecaller = "com.truecaller"
    val tries = listOf(
        TruecallerRoute.SEARCH to Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(truecaller)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, digits).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
        TruecallerRoute.WEB_LINK to Intent(Intent.ACTION_VIEW, Uri.parse("https://www.truecaller.com/search/in/$digits")).setPackage(truecaller),
        TruecallerRoute.DIAL_PAD to Intent(Intent.ACTION_DIAL, Uri.parse("tel:$e164")).setPackage(truecaller),
        TruecallerRoute.APP to packageManager.getLaunchIntentForPackage(truecaller),
        TruecallerRoute.WEBSITE to Intent(Intent.ACTION_VIEW, Uri.parse("https://www.truecaller.com/search/in/$digits")),
    )
    for ((route, intent) in tries) {
        if (intent == null) continue
        // Truecaller's own routes only when it says it takes them; the website is the last resort.
        if (route != TruecallerRoute.WEBSITE && intent.resolveActivity(packageManager) == null) continue
        if (runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
            if (BuildConfig.DEBUG) android.widget.Toast.makeText(this, route.note, android.widget.Toast.LENGTH_LONG).show()
            return route
        }
    }
    return TruecallerRoute.NONE
}

/** Places the call at once (needs the call permission); the dialer if that fails. */
fun Context.callNow(e164: String) {
    val ok = runCatching { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$e164")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok) dial(e164)
}

/** The phone's own dialer, with the number filled in. No call permission needed. */
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

/** The help address (BuildConfig.CONTACT_EMAIL); empty until there is one: then "Write to us" is hidden. */
val HELP_EMAIL: String = app.foundwith.BuildConfig.CONTACT_EMAIL
val PRIVACY_URL: String get() = BuildConfig.PRIVACY_URL
/** The terms of use and their grievance officer section, beside the privacy policy (whohas-app.web.app/terms). */
val TERMS_URL: String get() = BuildConfig.PUBLIC_URL.takeIf { it.startsWith("https://") }?.plus("/terms").orEmpty()
val GRIEVANCE_URL: String get() = TERMS_URL.takeIf { it.isNotEmpty() }?.plus("#grievance").orEmpty()

/** The web page that deletes an account without the app (Play's account deletion rule). The API serves it at /delete. */
val DELETE_URL: String get() = (BuildConfig.PUBLIC_URL.takeIf { it.startsWith("https://") } ?: BuildConfig.API_BASE_URL.trimEnd('/'))
    .takeIf { it.startsWith("https://") }?.plus("/delete").orEmpty()

// ---------------------------------------------------------------- building blocks

// Soft tints, each with letters of the same hue at 7:1 or better: light pairs on white, dark on grey.
private val AvatarColoursLight = listOf(
    Color(0xFFE7F3FF) to Color(0xFF0450C2),
    Color(0xFFE6F4EA) to Color(0xFF0D652D),
    Color(0xFFFCE8E6) to Color(0xFFA50E0E),
    Color(0xFFF3E8FD) to Color(0xFF6A1B9A),
    Color(0xFFFEF7E0) to Color(0xFF7A4F01),
)
private val AvatarColoursDark = listOf(
    Color(0xFF263951) to Color(0xFFA8C8FF),
    Color(0xFF1E3326) to Color(0xFF9ED8B2),
    Color(0xFF3B2626) to Color(0xFFF0B8B0),
    Color(0xFF33263F) to Color(0xFFD8B9EE),
    Color(0xFF3A3220) to Color(0xFFE8CF8E),
)

/** A circle with initials. The letters keep their size whatever the phone's font setting, so they always fit. */
@Composable
fun Avatar(seed: String, name: String, size: Dp = 40.dp) {
    val palette = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) AvatarColoursDark else AvatarColoursLight
    val (bg, fg) = palette[Math.floorMod(seed.hashCode(), palette.size)]
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
        // The name comes first; the small "what others call it" note gets what width is left.
        Text(
            name.text,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        name.tag?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** Opens the press-and-hold menu for a number (Copy number, Truecaller, Call back, Open card). Provided by Home. */
val LocalNumberMenu = androidx.compose.runtime.compositionLocalOf<((String) -> Unit)?> { null }

/** Tap opens the card; press and hold opens the number menu when [e164] is known. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun rowClick(e164: String?, onClick: (() -> Unit)?): Modifier {
    val menu = LocalNumberMenu.current
    if (onClick == null) return Modifier
    val hold = if (e164 != null && menu != null) ({ menu(e164) }) else null
    return Modifier.combinedClickable(onClick = onClick, onLongClick = hold)
}

/**
 * One row in every list: who, the one line, and an optional small line under it. The line wraps
 * to two lines rather than cutting off the count.
 */
/**
 * Large text (130% and up) on the phone (24 Nov 2026): rows put their button and tag under the
 * words instead of beside them, so names aren't squeezed to "+9…" on small phones.
 */
@Composable
fun bigText(): Boolean = LocalDensity.current.fontScale >= 1.25f

@Composable
fun NumberRow(
    leading: @Composable () -> Unit,
    name: ShownName,
    line: String,
    sub: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    e164: String? = null,
    tag: (@Composable () -> Unit)? = null,
) {
    val big = bigText()
    ListItem(
        modifier = rowClick(e164, onClick),
        leadingContent = leading,
        headlineContent = { NameText(name) },
        supportingContent = {
            Column {
                Text(line, maxLines = if (big) 3 else 2, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tag != null) Box(Modifier.padding(top = 4.dp)) { tag() }
                if (big && trailing != null) Box(Modifier.padding(top = 6.dp)) { trailing() }
            }
        },
        trailingContent = if (big) null else trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * One number in Updates, Contacts and the call log: who, then what kind of call or where it's from
 * (small), then who has it (blue when you know someone, grey otherwise).
 */
@Composable
fun InfoRow(
    leading: @Composable () -> Unit,
    name: ShownName,
    sub: String? = null,
    subColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    line: String? = null,
    lineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    isNew: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    e164: String? = null,
    tag: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(rowClick(e164, onClick))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        val big = bigText()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NameText(name)
            if (big) {
                // Large text: the small line, the tag, the line and the button each get the full width.
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = subColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                tag?.invoke()
                if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, color = lineColor, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (trailing != null) Box(Modifier.padding(top = 4.dp)) { trailing() }
            }
            // The small line under the name, with the Popular / Highly popular tag after it (as designed).
            if (!big && (sub != null || tag != null)) Row(verticalAlignment = Alignment.CenterVertically) {
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (sub != null && tag != null) Spacer(Modifier.width(8.dp))
                tag?.invoke()
            }
            if (!big && line != null) Text(line, style = MaterialTheme.typography.bodyMedium, color = lineColor, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (isNew) {
            Spacer(Modifier.width(8.dp))
            NewDot()
        }
        if (!big && trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** The blue dot for what's new since the app was last open. */
@Composable
fun NewDot() {
    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
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
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = colors,
        shape = RoundedCornerShape(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        // Two lines on large text rather than "Share in gro…" (24 Nov 2026).
        Text(text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A one-line strip above the tabs: offline, notifications off, phone settings. */
@Composable
fun Banner(icon: ImageVector, text: String, action: String? = null, warning: Boolean = false, onAction: () -> Unit = {},
           onClose: (() -> Unit)? = null) {
    val bg = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action) }
            if (onClose != null) androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
            }
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
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit,
                  cancel: String = "Cancel") {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel) } },
    )
}

// ---------------------------------------------------------------- WhoHas pieces

/** "WhoHas", with "Has" in blue. */
@Composable
fun Wordmark(size: TextUnit = 22.sp) {
    Text(
        buildAnnotatedString {
            append("Who")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Has") }
        },
        fontSize = size, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
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

/** "Live · just now" with a slow green pulse, or what's shown while offline. */
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
        Text("Live · ${agoText(checkedAt, now)}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A card on Home: white (dark grey in dark mode), a hairline edge, rounded. */
@Composable
fun WhCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    edge: Boolean = true,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = color, shape = RoundedCornerShape(12.dp),
        border = if (edge) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** A small rounded label in blue tint ("+5 this week"); strong = solid blue. */
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

/** "Share" (let them know who has their number): blue, at least 44 dp tall. After a tap it reads "Shared". */
@Composable
fun TellButton(told: Boolean, name: String, onClick: () -> Unit) {
    if (told) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Told", fontWeight = FontWeight.Bold)
            }
        }
        return
    }
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary),
    ) { Text("Tell", fontWeight = FontWeight.Bold) }
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

/** A line with a blue tick, for "Then you can:" lists. */
@Composable
fun TickLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp).size(22.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Your own number: a card with a light blue edge and the count in blue. */
@Composable
fun HeroCard(
    number: String?,
    count: Int,
    countLabel: String,
    line: String?,
    people: List<Pair<String, String>> = emptyList(),
    chip: String? = null,
    onClick: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = HeroColor, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, HeroEdge),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("Your number", style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.weight(1f))
                if (number != null) Text(number, style = MaterialTheme.typography.labelLarge, color = muted)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(HolderText.grouped(count), fontSize = 48.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(countLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (line != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (people.isNotEmpty()) {
                        PeopleStack(people, ring = HeroColor)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(line, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            if (chip != null) Chip(chip, Icons.Filled.KeyboardArrowUp)
            extra?.invoke()
        }
    }
}

/** Your number before anyone has it: "No one on WhoHas has your number yet", then your level. */
@Composable
fun EmptyHero(number: String?) {
    Surface(color = HeroColor, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, HeroEdge), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("Your number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (number != null) Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("No one on WhoHas has your number yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LevelBlock(0)
        }
    }
}

/**
 * Press and hold a number in Updates, Contacts or Call log: Copy number, Truecaller, Call back, Open card.
 * Most unknown callers have no name, so copying the number to search elsewhere is the common need.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NumberMenuSheet(e164: String, name: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        NumberMenu(e164, name, onOpen, onDismiss)
    }
}

/** What the press-and-hold sheet holds. */
@Composable
fun NumberMenu(e164: String, name: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(if (name == shown(e164)) shown(e164) else "$name · ${shown(e164)}", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        MenuRow("Copy number") { context.copyNumber(e164); onDismiss() }
        MenuRow("Search in Truecaller") { context.searchInTruecaller(e164); onDismiss() }
        MenuRow("Call back") { context.dial(e164); onDismiss() }
        MenuRow("Open card") { onDismiss(); onOpen() }
    }
}

/**
 * Your number, fixed just above the tabs and the same on Call log, Contacts and Updates (Y, 22 Nov 2026):
 * how many people have it, this week's rise and your level. A tap opens your own card.
 */
@Composable
fun YourNumberBar(total: Int, up: Int, onClick: (() -> Unit)?, onShare: (() -> Unit)? = null) {
    val blue = MaterialTheme.colorScheme.primary
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open your number", onClick = onClick) else Modifier)
                .then(Modifier.semantics(mergeDescendants = true) {})
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            androidx.compose.material3.Icon(Icons.Filled.Person, contentDescription = null, tint = blue,
                modifier = Modifier.size(20.dp))
            Text(
                if (total <= 0) androidx.compose.ui.text.AnnotatedString("No one on WhoHas has your number yet")
                else androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = blue, fontWeight = FontWeight.Bold))
                    append(HolderText.grouped(total))
                    pop()
                    append(if (total == 1) " person has your number" else " people have your number")
                },
                color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.weight(1f),
            )
            // One thin line on most phones: no arrow (the whole bar opens your card), smaller words.
            if (!bigText()) {
                if (up > 0) Text("+${HolderText.grouped(up)}", color = LiveGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                LevelTag(Levels.of(total))
            }
        }
        if (bigText()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 46.dp, end = 16.dp, bottom = 6.dp)) {
            LevelTag(Levels.of(total))
            if (up > 0) Text("+${HolderText.grouped(up)}", color = LiveGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
        // How far the next level is, and Share (Y, 23 Nov 2026): the reason to share, where it is
        // always in sight. None at the top level.
        Levels.step(total)?.let { step -> NextLevelLine(step, Levels.barLine(total)!!, onShare) }
        }
    }
}

/** Under your number in the bar: a thin line, "2 more → Popular" and Share. */
@Composable
private fun NextLevelLine(step: Levels.Step, words: String, onShare: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
    ) {
        // Large text: the words come first and the line shrinks to what's left (never Share).
        if (!bigText()) ProgressLine(step.done, Modifier.weight(1f), track = MaterialTheme.colorScheme.surface)
        Text(words, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, modifier = if (bigText()) Modifier.weight(1f) else Modifier)
        if (onShare != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 36.dp)
                    .clickable(onClickLabel = "Share WhoHas in a group", role = androidx.compose.ui.semantics.Role.Button, onClick = onShare)
                    .padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Share", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A thin rounded line, [done] of it (0 to 1) in blue. */
@Composable
fun ProgressLine(done: Float, modifier: Modifier = Modifier, track: Color = MaterialTheme.colorScheme.surfaceVariant, height: Dp = 6.dp) {
    Box(modifier.height(height).background(track, RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth(done.coerceIn(0.04f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
    }
}

/**
 * Your own level on your card (23 Nov 2026): your tag, the line to the next one and its tag, and
 * "2 more people and you’re Popular". At the top: the tag and "Top level. 52 people have your number".
 */
@Composable
fun LevelProgress(total: Int) {
    val level = Levels.of(total)
    val step = Levels.step(total)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LevelTag(level)
            if (step != null) {
                ProgressLine(step.done, Modifier.weight(1f), height = 8.dp)
                LevelTag(step.next)
            }
        }
        Text(Levels.progress(total)?.replaceFirstChar { it.uppercase() } ?: Levels.topLine(total),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

/** The faces of people who joined with your link that this phone knows; a plain person when it knows none. */
@Composable
fun JoinFaces(joins: List<LinkJoin>) {
    val known = joins.filter { it.name != null }.take(2)
    if (known.isEmpty()) IconAvatar(Icons.Filled.Person, accent = true)
    else PeopleStack(known.map { it.ref to it.name!! }, ring = MaterialTheme.colorScheme.surface)
}

/** A row on your own card's "what grows your count" box: a picture, a bold line, a grey line. */
@Composable
fun GrowRow(leading: @Composable () -> Unit, title: String, line: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** The copy icon beside a number on its card. Android 13+ shows its own "Copied"; older phones get a toast. */
@Composable
fun CopyNumberButton(e164: String) {
    val context = LocalContext.current
    androidx.compose.material3.OutlinedIconButton(onClick = { context.copyNumber(e164) },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(app.foundwith.R.drawable.ic_copy),
            contentDescription = "Copy number", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/**
 * A small grey "Truecaller" button on a row for a number that isn't in your contacts (Y, 24 Nov
 * 2026, option A): copies the number and opens Truecaller on it, as the card's button does.
 */
@Composable
fun TruecallerPill(e164: String) {
    val context = LocalContext.current
    androidx.compose.material3.FilledTonalButton(
        onClick = { context.searchInTruecaller(e164) },
        shape = RoundedCornerShape(50),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        modifier = Modifier.heightIn(min = 34.dp).semantics { contentDescription = "Search ${app.foundwith.core.PhoneNumbers.display(e164)} in Truecaller" },
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text("Truecaller", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The buttons under a card: Truecaller (copies, then opens it), and Call back after a call from a number not in contacts. */
@Composable
fun CardButtons(e164: String, callBack: Boolean) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        // Grey buttons, as Facebook's secondary buttons.
        androidx.compose.material3.FilledTonalButton(onClick = { context.searchInTruecaller(e164) }, modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(8.dp)) { Text("Truecaller", maxLines = 1) }
        if (callBack) {
            androidx.compose.material3.FilledTonalButton(onClick = { context.dial(e164) }, modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(8.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Call back", maxLines = 1)
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp))
}

// ---------------------------------------------------------------- levels

/**
 * A level as a small tag (docs/WHOHAS_COPY.md): Not popular is grey and outlined, Popular is blue
 * tint, Highly popular is blue tint with a blue outline.
 */
@Composable
fun LevelTag(level: app.foundwith.core.Level, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bg: Color
    val fg: Color
    val edge: BorderStroke
    when (level) {
        app.foundwith.core.Level.HIGHLY_POPULAR -> { bg = scheme.primaryContainer; fg = scheme.primary; edge = BorderStroke(1.5.dp, scheme.primary) }
        app.foundwith.core.Level.POPULAR -> { bg = scheme.primaryContainer; fg = scheme.onPrimaryContainer; edge = BorderStroke(1.dp, scheme.primaryContainer) }
        app.foundwith.core.Level.NOT_POPULAR -> { bg = Color.Transparent; fg = scheme.onSurfaceVariant; edge = BorderStroke(1.dp, scheme.onSurfaceVariant) }
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), border = edge, modifier = modifier) {
        Text(level.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** The tag on a list row: only Popular and Highly popular. */
fun rowTag(total: Int?, lowerBound: Boolean = false): (@Composable () -> Unit)? =
    total?.let { app.foundwith.core.Levels.rowTag(it, lowerBound) }?.let { level -> { LevelTag(level) } }

/**
 * Your own level, inside your number's card: just the tag and its short line (Y, 22 Nov: the bar,
 * "10 more people and you're Popular", the link and the pooling line took too much room).
 */
@Composable
fun LevelBlock(total: Int, modifier: Modifier = Modifier) {
    val level = app.foundwith.core.Levels.of(total)
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LevelTag(level)
        val why = app.foundwith.core.Levels.why(level)
        if (why.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    }
}

/** At the top of Updates for one day after reaching a level: "You're Popular now", with Share in group. */
@Composable
fun LevelUpBanner(total: Int, onShare: () -> Unit) {
    val level = app.foundwith.core.Levels.of(total)
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("You’re ${level.label} now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(app.foundwith.core.Levels.reachedText(total), style = MaterialTheme.typography.bodyMedium)
                }
            }
            PrimaryButton("Share in group", onShare, icon = Icons.Filled.Share)
        }
    }
}

/** On a card: this number's level beside yours, with where it stands. */
@Composable
fun CompareBox(theirs: app.foundwith.core.Level, mineTotal: Int) {
    val mine = app.foundwith.core.Levels.of(mineTotal)
    val result = app.foundwith.core.Levels.compare(theirs, mineTotal)
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
                LevelTag(theirs)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("You", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
                LevelTag(mine)
            }
            Text(result.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
