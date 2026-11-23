package app.foundwith.call

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.foundwith.MainActivity
import app.foundwith.R
import app.foundwith.graph
import app.foundwith.core.AfterCallNote
import app.foundwith.core.Alert

/**
 * WhoHas's notifications (22 Nov 2026). One Android channel per kind, so people can also quieten
 * any of them in Android's own settings:
 *  - "Calls from unknown numbers" (with sound): a missed call from a number not in your contacts, when someone has it.
 *  - "Other calls (silent)": every other call WhoHas tells about, received or dialled. No sound.
 *  - "Changes to your numbers": a new person has your number, a count went up. Merged into one.
 *  - "Friends who join": someone in your contacts joined WhoHas. No sound.
 *  - "Your level": you reached Popular or Highly popular, or you are close. At most one nudge a week.
 * One notification per number: a second call from the same number updates it. There is no daily summary.
 */
class Notifier(private val context: Context) {

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(CALLS_SOUND, "Calls from unknown numbers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "After a missed call from a number not in your contacts: who you know has it."
            },
            NotificationChannel(CALLS_QUIET, "Other calls (silent)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "After other calls, without sound."
            },
            NotificationChannel(CHANGES, "Changes to your numbers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "When someone new has your number or a number you have. No sound."
            },
            NotificationChannel(JOINED, "Friends who join", NotificationManager.IMPORTANCE_LOW).apply {
                description = "When someone in your contacts joins WhoHas. No sound."
            },
            NotificationChannel(LEVEL, "Your level", NotificationManager.IMPORTANCE_LOW).apply {
                description = "When you reach a new level, or are close. No sound."
            },
        ))
        // Channels from earlier versions.
        runCatching { manager.deleteNotificationChannel("incoming_calls") }
        runCatching { manager.deleteNotificationChannel("evening") }
    }

    fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Shows (or updates) the notification for one caller. Tapping it opens their card. */
    fun postCall(note: AfterCallNote) {
        if (note.alert == Alert.NONE || !canPost()) return
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_NUMBER)
            .putExtra(EXTRA_NUMBER, note.number)
            .putExtra(EXTRA_KEY, context.graph.store.openKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, note.number.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (note.alert == Alert.SOUND) CALLS_SOUND else CALLS_QUIET
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setContentTitle(note.title)
            .setContentText(note.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (note.alert == Alert.SOUND) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setSilent(note.alert != Alert.SOUND)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setGroup(GROUP_CALLS)
            .addAction(0, "Copy number", action(ACTION_COPY, note.number))
            .addAction(0, "Truecaller", action(ACTION_TRUECALLER, note.number))
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_CALL, note.number.hashCode(), notification) }
    }

    /** A button on a notification: opens WhoHas's invisible [NumberActionActivity] with the number. */
    private fun action(name: String, e164: String): PendingIntent {
        val intent = Intent(context, NumberActionActivity::class.java).setAction(name).putExtra(EXTRA_NUMBER, e164)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return PendingIntent.getActivity(context, (name + e164).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "2 changes on WhoHas": silent, opens Updates. One notification that is replaced by the next. */
    fun postChanges(title: String, text: String) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANGES)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setContentTitle(title)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(0, "Open Updates", pending)
        if (text.isNotEmpty()) builder.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_CHANGES, 1, builder.build()) }
    }

    /** "You're Popular now" or "2 more people and you're Highly popular". Silent; opens Updates (its banner is there). */
    fun postLevel(title: String, text: String) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, LEVEL)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(0, "Share in group", action(ACTION_SHARE_GROUP, ""))
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_LEVEL, 2, notification) }
    }

    /** "Priya joined WhoHas · Has your number". Silent; tapping opens their card. */
    fun postJoined(e164: String, title: String, text: String?) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_NUMBER)
            .putExtra(EXTRA_NUMBER, e164)
            .putExtra(EXTRA_KEY, context.graph.store.openKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, ("j" + e164).hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, JOINED)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setContentTitle(title)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (text != null) builder.setContentText(text)
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_JOINED, e164.hashCode(), builder.build()) }
    }

    fun cancelAll() = runCatching { NotificationManagerCompat.from(context).cancelAll() }

    companion object {
        const val CALLS_SOUND = "callers"
        const val CALLS_QUIET = "calls_quiet"
        const val CHANGES = "changes"
        const val LEVEL = "level"
        const val JOINED = "joined"
        private const val TAG_JOINED = "joined"
        const val ACTION_OPEN_NUMBER = "app.foundwith.OPEN_NUMBER"
        const val ACTION_OPEN_UPDATES = "app.foundwith.OPEN_UPDATES"
        const val ACTION_COPY = "app.foundwith.COPY_NUMBER"
        const val ACTION_TRUECALLER = "app.foundwith.TRUECALLER"
        const val ACTION_SHARE_GROUP = "app.foundwith.SHARE_GROUP"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_KEY = "key"
        private const val TAG_CALL = "call"
        private const val TAG_CHANGES = "changes"
        private const val TAG_LEVEL = "level"
        private const val GROUP_CALLS = "calls"
    }
}
