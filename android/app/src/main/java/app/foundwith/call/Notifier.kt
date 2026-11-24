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
 * WhoHas's notifications (22 Nov 2026). One Android channel per kind, so people can also quieten
 * any of them in Android's own settings:
 *  - "Calls from unknown numbers" (with sound): a missed call from a number not in your contacts, when someone has it.
 *  - "Other calls (no sound)": every other call WhoHas tells about, received or dialled. It pops up
 *    on the screen like the first kind (23 Nov 2026: so it's seen at once), but makes no sound.
 *  - "Changes to your numbers": a new person has your number, a count went up, someone you know
 *    now has a caller's number. One notification per number (23 Nov 2026), popping up, no sound.
 *  - "Friends who join": someone in your contacts joined WhoHas. Pops up, no sound.
 *  - "Your level": you reached Popular or Highly popular, or you are close. At most one nudge a week.
 * One notification per number: a second call from the same number updates it. There is no daily summary.
 */
/** Facebook-style blue for the small icon and app name in notifications (same as the app). */
private const val ACCENT = 0xFF0862F5.toInt()

class Notifier(private val context: Context) {

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(CALLS_SOUND, "Calls from unknown numbers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "After a missed call from a number not in your contacts: who you know has it."
            },
            // High importance pops the notification up on the screen; no sound and no vibration.
            NotificationChannel(CALLS_QUIET, "Other calls (no sound)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "After other calls: shows on the screen, without sound."
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(CHANGES, "Changes to your numbers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Each time someone new has your number or a number you have. Shows on the screen, no sound."
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(JOINED, "Friends who join", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone in your contacts joins WhoHas. Shows on the screen, no sound."
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(LAST_CALL, "After every call (quiet)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Calls with nothing new, in the notification panel only. No pop-up, no sound."
            },
            NotificationChannel(LEVEL, "Your level", NotificationManager.IMPORTANCE_LOW).apply {
                description = "When you reach a new level, or are close. No sound."
            },
        ))
        // Channels from earlier versions.
        runCatching { manager.deleteNotificationChannel("incoming_calls") }
        runCatching { manager.deleteNotificationChannel("evening") }
        // The quiet call channel before 23 Nov 2026 didn't pop up; a channel's importance can't be raised.
        runCatching { manager.deleteNotificationChannel("calls_quiet") }
        // Changes and joins before 23 Nov 2026 were quiet channels that didn't pop up.
        runCatching { manager.deleteNotificationChannel("changes") }
        runCatching { manager.deleteNotificationChannel("joined") }
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
            .putExtra(EXTRA_KEY, app.foundwith.data.LocalStore(context).openKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, note.number.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (note.alert == Alert.QUIET) { postLastCall(note, pending); return }
        val channel = if (note.alert == Alert.SOUND) CALLS_SOUND else CALLS_QUIET
        val notification = NotificationCompat.Builder(context, channel)
            // Something new or changed: "New" beside WhoHas at the top of the notification (24 Nov 2026).
            .setSubText(if (note.isNew) "New" else null)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setColor(ACCENT)
            .setContentTitle(note.title)
            .setContentText(note.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            // Both kinds pop up on the screen; only the first has sound (its channel). Not setSilent:
            // Android never pops up a notification marked silent.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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

    /**
     * A call with nothing new (Y, 24 Nov 2026): one quiet "Last call" note in the notification panel,
     * no pop-up and no sound; the next call replaces it. Share in group when nobody has the number.
     */
    private fun postLastCall(note: AfterCallNote, open: PendingIntent) {
        val builder = NotificationCompat.Builder(context, LAST_CALL)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setColor(ACCENT)
            .setSubText("Last call")
            .setContentTitle(note.title)
            .setContentText(note.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (note.nobody) builder.addAction(0, "Share in group", action(ACTION_SHARE_GROUP, ""))
        else builder.addAction(0, "Copy number", action(ACTION_COPY, note.number))
        builder.addAction(0, "Truecaller", action(ACTION_TRUECALLER, note.number))
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_LAST_CALL, 1, builder.build()) }
    }

    /** A button on a notification: opens WhoHas's invisible [NumberActionActivity] with the number. */
    private fun action(name: String, e164: String): PendingIntent {
        val intent = Intent(context, NumberActionActivity::class.java).setAction(name).putExtra(EXTRA_NUMBER, e164)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return PendingIntent.getActivity(context, (name + e164).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * One change to one number (23 Nov 2026): "Raju Plumber · 2 more people have this number · now 14".
     * Pops up without sound and opens that number's card (your own for [e164] = your number). A later
     * change to the same number replaces it. Android stacks four or more under WhoHas by itself.
     */
    fun postChange(e164: String?, title: String, text: String) {
        if (!canPost()) return
        val open = if (e164 == null) {
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_UPDATES)
        } else {
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_NUMBER)
                .putExtra(EXTRA_NUMBER, e164)
                .putExtra(EXTRA_KEY, app.foundwith.data.LocalStore(context).openKey)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = (e164 ?: "updates").hashCode()
        val pending = PendingIntent.getActivity(context, ("c" + id).hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANGES)
            .setSubText("New")      // a change: marked New, as on calls (24 Nov 2026)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (text.isNotEmpty()) builder.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_CHANGES, id, builder.build()) }
    }

    /** A number's card was opened: its change and join notifications go (23 Nov 2026). */
    fun cancelNumber(e164: String) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(TAG_CHANGES, e164.hashCode())
            manager.cancel(TAG_JOINED, e164.hashCode())
        }
    }

    /** "2 changes on WhoHas": opens Updates. One notification that is replaced by the next (before 23 Nov 2026). */
    fun postChanges(title: String, text: String) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANGES)
            .setSubText("New")      // a change: marked New, as on calls (24 Nov 2026)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setColor(ACCENT)
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
            .setColor(ACCENT)
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

    /**
     * "Ravi Kumar joined with your link" (23 Nov 2026). Silent, on the Your level channel, with
     * Share in group; one per person. Opens their card when the phone has their number, else Updates.
     */
    fun postLinkJoined(ref: String, e164: String?, title: String, text: String) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (e164 != null) {
            open.setAction(ACTION_OPEN_NUMBER).putExtra(EXTRA_NUMBER, e164)
                .putExtra(EXTRA_KEY, app.foundwith.data.LocalStore(context).openKey)
        } else {
            open.setAction(ACTION_OPEN_UPDATES)
        }
        val pending = PendingIntent.getActivity(context, ("l" + ref).hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, JOINED)
            .setSubText("New")
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)   // pops up, like a friend joining (24 Nov 2026)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(0, "Share in group", action(ACTION_SHARE_GROUP, ""))
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_LINKED, ref.hashCode(), notification) }
    }

    /** "Priya joined WhoHas · Has your number". Pops up without sound; tapping opens their card. */
    fun postJoined(e164: String, title: String, text: String?) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_NUMBER)
            .putExtra(EXTRA_NUMBER, e164)
            .putExtra(EXTRA_KEY, app.foundwith.data.LocalStore(context).openKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, ("j" + e164).hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, JOINED)
            .setSubText("New")      // a change: marked New, as on calls (24 Nov 2026)
            .setSmallIcon(R.drawable.ic_stat_found_with)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (text != null) builder.setContentText(text)
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(TAG_JOINED, e164.hashCode(), builder.build()) }
    }

    fun cancelAll() = runCatching { NotificationManagerCompat.from(context).cancelAll() }

    companion object {
        const val CALLS_SOUND = "callers"
        const val CALLS_QUIET = "calls_other"
        const val LAST_CALL = "last_call"
        private const val TAG_LAST_CALL = "last_call"
        const val CHANGES = "number_changes"
        const val LEVEL = "level"
        const val JOINED = "friends_joined"
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
        private const val TAG_LINKED = "linked"
        private const val GROUP_CALLS = "calls"
    }
}
