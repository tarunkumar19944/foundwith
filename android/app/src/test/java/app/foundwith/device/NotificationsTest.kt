package app.foundwith.device

import app.foundwith.ui.TruecallerRoute
import app.foundwith.ui.searchInTruecaller
import app.foundwith.data.LookupResult
import kotlinx.coroutines.runBlocking
import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.foundwith.call.Notifier
import app.foundwith.call.NumberActionActivity
import app.foundwith.core.AfterCallNote
import app.foundwith.core.Alert
import app.foundwith.data.LocalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * WhoHas's notifications and their buttons, run on Android code (Robolectric). The plain
 * [Application] stands in for the app class, so nothing signs in or schedules work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val notifier = Notifier(context)

    @Before
    fun allowNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.createChannels()
    }

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun fiveChannelsAndNoDailySummary() {
        assertEquals(setOf("callers", "calls_quiet", "changes", "joined", "level"), manager.notificationChannels.map { it.id }.toSet())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("callers").importance)
        for (quiet in listOf("calls_quiet", "changes", "joined", "level")) {
            assertEquals(quiet, NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(quiet).importance)
        }
    }

    @Test
    fun aMissedCallHasCopyAndTruecallerButtons() {
        notifier.postCall(AfterCallNote(Alert.SOUND, "Missed call: +91 77609 34418", "Known to Priya and Meena", "+917760934418"))
        val n = posted().single()
        assertEquals("callers", n.channelId)
        assertEquals(listOf("Copy number", "Truecaller"), n.actions.map { it.title.toString() })
        assertEquals("Missed call: +91 77609 34418", n.extras.getString("android.title"))
    }

    @Test
    fun aQuietCallGoesToTheSilentChannelAndASecondCallReplacesIt() {
        notifier.postCall(AfterCallNote(Alert.SILENT, "You called +91 97311 20456", "Known to Priya", "+919731120456"))
        notifier.postCall(AfterCallNote(Alert.SILENT, "Call from +91 97311 20456", "Known to Priya and Meena", "+919731120456"))
        val n = posted().single()
        assertEquals("calls_quiet", n.channelId)
        assertEquals("Call from +91 97311 20456", n.extras.getString("android.title"))
    }

    @Test
    fun nothingIsPostedForACallWorthNothing() {
        notifier.postCall(AfterCallNote.NONE)
        assertTrue(posted().isEmpty())
    }

    @Test
    fun changesAreOneNotificationWithOpenUpdates() {
        notifier.postChanges("2 changes on WhoHas", "1 more person has your number. +91 98450 77120 is now known to Meena.")
        notifier.postChanges("3 changes on WhoHas", "And more.")
        val n = posted().single()
        assertEquals("changes", n.channelId)
        assertEquals("3 changes on WhoHas", n.extras.getString("android.title"))
        assertEquals(listOf("Open Updates"), n.actions.map { it.title.toString() })
    }

    @Test
    fun aLevelHasShareInGroup() {
        notifier.postLevel("You’re Popular now", "10 people have your number. 40 more and you’re Highly popular.")
        val n = posted().single()
        assertEquals("level", n.channelId)
        assertEquals(listOf("Share in group"), n.actions.map { it.title.toString() })
    }

    @Test
    fun withoutPermissionNothingIsPosted() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.canPost())
        notifier.postChanges("2 changes on WhoHas", "x")
        assertTrue(posted().isEmpty())
    }

    @Test
    fun copyNumberPutsTheTenDigitsOnTheClipboard() {
        val intent = Intent(context, NumberActionActivity::class.java).setAction(Notifier.ACTION_COPY)
            .putExtra(Notifier.EXTRA_NUMBER, "+917760934418")
        val activity = Robolectric.buildActivity(NumberActionActivity::class.java, intent).setup().get()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("7760934418", clip.getItemAt(0).text.toString())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun truecallerCopiesAndOpensTheWebSearchWhenTheAppIsNotInstalled() {
        val intent = Intent(context, NumberActionActivity::class.java).setAction(Notifier.ACTION_TRUECALLER)
            .putExtra(Notifier.EXTRA_NUMBER, "+917760934418")
        val activity = Robolectric.buildActivity(NumberActionActivity::class.java, intent).setup().get()
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://www.truecaller.com/search/in/7760934418", opened.dataString)
        assertEquals("7760934418", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun theStoreRemembersWhatWasToldForTheLast200NumbersOnly() {
        val store = LocalStore(context)
        assertNull(store.lastNotified("+917760934418"))
        for (i in 0 until 205) store.setLastNotified("+9177600%05d".format(i), i)
        assertNull(store.lastNotified("+917760000000"))          // the oldest five are forgotten
        assertNull(store.lastNotified("+917760000004"))
        assertEquals(5, store.lastNotified("+917760000005"))
        assertEquals(204, store.lastNotified("+917760000204"))
        store.setLastNotified("+917760000204", 210)               // telling again updates, not duplicates
        assertEquals(210, store.lastNotified("+917760000204"))
    }

    @Test
    fun notificationSwitchesAreOnUntilTurnedOff() {
        val store = LocalStore(context)
        assertTrue(store.notifyUnknownCalls && store.notifyOtherCalls && store.notifyChanges && store.notifyJoined && store.notifyLevel)
        store.notifyLevel = false
        assertFalse(LocalStore(context).notifyLevel)
        assertFalse(store.levelReachedRecently(System.currentTimeMillis()))
        store.levelReachedAt = System.currentTimeMillis() - 1000
        assertTrue(store.levelReachedRecently(System.currentTimeMillis()))
    }

    @Test
    fun aCallsLookupIsKeptForItsCardForOneDay() = runBlocking {
        val store = LocalStore(context)
        val now = 1_800_000_000_000L
        assertNull(store.callLookup("+917760934418", now))
        store.saveCallLookup("+917760934418", LookupResult(ref = "r", number = "+917760934418", total = 1), now)
        assertEquals(1, store.callLookup("+917760934418", now + 60_000)?.result?.total)    // what the notification said
        assertNull(store.callLookup("+917760934418", now + 25 * 3_600_000L))                // gone after a day
        for (i in 0 until 55) store.saveCallLookup("+9177600%05d".format(i), LookupResult(ref = "r$i", number = "x", total = i), now + i)
        assertNull(store.callLookup("+917760000004", now + 100))                            // only the last 50 are kept
        assertEquals(54, store.callLookup("+917760000054", now + 100)?.result?.total)
    }

    @Test
    fun updatesSeenStartsFromTheLastTimeTheAppWasLeft() {
        val store = LocalStore(context)
        store.lastOpenedAt = 500L
        assertEquals(500L, store.updatesSeenAt)   // before Updates was ever looked at
        store.updatesSeenAt = 900L
        assertEquals(900L, store.updatesSeenAt)
    }

    @Test
    fun truecallerOpensItsSearchWhenItAcceptsTheNumber() {
        val application = context as android.app.Application
        val pm = org.robolectric.Shadows.shadowOf(application.packageManager)
        // No Truecaller: its website search.
        assertEquals(TruecallerRoute.WEBSITE, context.searchInTruecaller("+919845077120"))
        assertEquals("https://www.truecaller.com/search/in/9845077120", org.robolectric.Shadows.shadowOf(application).nextStartedActivity.dataString)
        // Truecaller with a "search with Truecaller" text action: that, with the number.
        val search = android.content.ComponentName("com.truecaller", "com.truecaller.SearchText")
        pm.addActivityIfNotPresent(search)
        pm.addIntentFilterForActivity(search, android.content.IntentFilter(android.content.Intent.ACTION_PROCESS_TEXT).apply { addDataType("text/plain") })
        assertEquals(TruecallerRoute.SEARCH, context.searchInTruecaller("+919845077120"))
        val started = org.robolectric.Shadows.shadowOf(application).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_PROCESS_TEXT, started.action)
        assertEquals("com.truecaller", started.`package`)
        assertEquals("9845077120", started.getStringExtra(android.content.Intent.EXTRA_PROCESS_TEXT))
    }
}
