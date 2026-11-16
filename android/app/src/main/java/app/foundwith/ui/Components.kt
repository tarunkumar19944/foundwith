package app.foundwith.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** A name with its small grey tag ("popular name", "name they gave") when it isn't the viewer's own. */
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
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(48.dp),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(text) }
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
