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
import app.foundwith.core.AfterCallNote
import app.foundwith.core.Alert

/**
 * Found With's notifications. Three channels, so people can quieten any of them in Android's own
 * settings too:
 *  - "Callers" (with sound): a missed call from a number you haven't saved, when someone has it.
 *  - "Calls, quietly": everything else after a call. No sound, no vibration.
 *  - "Evening note": once a day; the server sends it on this channel.
 * One notification per number: a second call from the same number updates it.
 */
class Notifier(private val context: Context) {

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(CALLS_SOUND, "Callers you haven't saved", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "After a missed call from a number you haven't saved: who you know has it."
            },
            NotificationChannel(CALLS_QUIET, "Calls, quietly", NotificationManager.IMPORTANCE_LOW).apply {
                description = "After other calls, without sound."
            },
            NotificationChannel(EVENING, "Evening note", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Once a day, only if something changed."
            },
        ))
        // The caller-ID panel's channel from earlier versions.
        runCatching { manager.deleteNotificationChannel("incoming_calls") }
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
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_CALL, note.number.hashCode(), notification) }
    }

    /** The evening note when it arrives while the app is open (otherwise Android shows the server's copy). */
    fun postEvening(title: String, body: String) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, EVENING)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_EVENING, 1, notification) }
    }

    fun cancelAll() = runCatching { NotificationManagerCompat.from(context).cancelAll() }

    companion object {
        const val CALLS_SOUND = "callers"
        const val CALLS_QUIET = "calls_quiet"
        const val EVENING = "evening"
        const val ACTION_OPEN_NUMBER = "app.foundwith.OPEN_NUMBER"
        const val ACTION_OPEN_UPDATES = "app.foundwith.OPEN_UPDATES"
        const val EXTRA_NUMBER = "number"
        private const val TAG_CALL = "call"
        private const val TAG_EVENING = "evening"
        private const val GROUP_CALLS = "calls"
    }
}
