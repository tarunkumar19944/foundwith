package app.foundwith

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The app as a person uses it, on a real phone or emulator: sign in, allow, look around every
 * screen, get a missed call, open the notification, change settings, sign out. The steps run in
 * name order and share one sign-in. Runs in Firebase Test Lab (.github/workflows/phone-tests.yml).
 *
 * Instrumentation arguments (Test Lab: --environment-variables):
 *   fwCode    the code of the test number (required; from Secret Manager, never in the repo)
 *   fwNumber  the test number to sign in with, 10 digits (default 9000000010)
 *   fwCaller  a number whose owner is on Found With with a name (default +919000000009)
 *
 * Every device in a run signs in as the same test account at the same time, so the checks are
 * about what the app shows and does, not about exact counts.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PhoneJourneyTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val args get() = InstrumentationRegistry.getArguments()
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    // ------------------------------------------------------------------ steps

    @Test
    fun step01_signsInWithTheTestNumber() {
        shot("01-start")
        if (onHome(10_000)) return
        val code = args.getString("fwCode")
        assumeTrue("fwCode not given: can't sign in", !code.isNullOrBlank())
        tap("Type your number")
        waitFor("Your mobile number")
        field().performTextInput(args.getString("fwNumber") ?: "9000000010")
        shot("02-number")
        tap("Send code")
        waitFor("Enter the code", 90_000)
        shot("03-code")
        field().performTextInput(code!!)
        assertTrue("Home didn't open after the code", onHome(90_000))
        shot("04-home")
    }

    @Test
    fun step02_allowsContactsAndCalls() {
        assertTrue(onHome(30_000))
        if (exists("Allow")) {
            shot("05-allow-card")
            tap("Allow")
        }
        waitFor("Updates", 120_000)
        if (exists("Your name on Found With") && exists("Not now")) tap("Not now")
        shot("06-ready")
    }

    @Test
    fun step03_contactsTabShowsTheSavedContacts() {
        waitFor("Contacts", 60_000)
        tap("Contacts")
        scrollTo("FW Test Plumber")
        shot("07-contacts")
        tap("FW Test Plumber")
        waitForAny(listOf("Found with", "Only you have this number", "Found with no one yet"), 30_000)
        shot("08-card")
        compose.onNode(hasContentDescription("More")).performClick()
        waitFor("Share this number")
        shot("09-card-menu")
        device.pressBack()
        back()
        waitFor("Contacts")
    }

    @Test
    fun step04_searchFindsANumberAndNames() {
        waitFor("Contacts", 60_000)
        search("Clinic")
        waitFor("FW Test Clinic")
        shot("10-search-name")
        search("98765")
        waitFor("Type the full number")
        search("9000000090")
        waitForAny(listOf("Found with", "Only you have this number", "Found with no one yet",
            "Today's search limit is over"), 30_000)
        shot("11-search-number")
        search("")
        waitFor("Contacts")
    }

    @Test
    fun step05_callsTabShowsRecentCalls() {
        assumeTrue("contactsOnly build has no call log", BuildConfig.CALL_LOG)
        waitFor("Calls", 60_000)
        tap("Calls")
        scrollToAny(listOf("90000 00092", "FW Test Plumber"))
        shot("12-calls")
    }

    @Test
    fun step06_aMissedCallFromSomeoneOnFoundWithIsNotified() {
        assumeTrue("contactsOnly build has no call log", BuildConfig.CALL_LOG)
        val caller = args.getString("fwCaller") ?: "+919000000009"
        addCall(InstrumentationRegistry.getInstrumentation().targetContext, caller, CallLog.Calls.MISSED_TYPE, System.currentTimeMillis())
        // Coming back to the app checks calls at once (the call-log trigger would too, a little later).
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        device.openNotification()
        val note = device.wait(Until.findObject(By.textContains("Test Caller")), 120_000)
            ?: device.wait(Until.findObject(By.textContains("00009")), 5_000)
        shotDevice("13-notification")
        if (note == null) device.pressBack()   // close the shade for the next steps
        assertTrue("No after-call notification for the missed call", note != null)
        note!!.click()
        waitFor("Test Caller", 30_000)
        shot("14-caller-card")
        back()
    }

    @Test
    fun step07_settingsPagesOpen() {
        assertTrue(onHome(30_000))
        compose.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Your data and account")
        shot("15-settings")
        for ((row, inside) in listOf(
            "Notifications" to "Evening note",
            "Your data and account" to "Download my data",
            "Help" to "How it works",
            "Invite friends" to "Share your link",
        )) {
            tap(row)
            waitFor(inside)
            shot("16-" + row.lowercase().replace(' ', '-'))
            back()
            waitFor("Your data and account")
        }
        tap("Your data and account")
        tap("Blocked people")
        waitFor("Blocked people")
        back()
        back()
        back()
        assertTrue(onHome(10_000))
    }

    @Test
    fun step08_signsOut() {
        assertTrue(onHome(30_000))
        compose.onNode(hasContentDescription("Settings")).performClick()
        tap("Your data and account")
        tap("Sign out")
        waitFor("Sign out?")
        // The dialog's confirm button is the last "Sign out" on screen (the first is the row).
        val signOuts = compose.onAllNodes(hasText("Sign out"))
        signOuts[signOuts.fetchSemanticsNodes().size - 1].performClick()
        waitFor("Type your number", 30_000)
        shot("17-signed-out")
    }

    // ------------------------------------------------------------------ helpers

    private fun onHome(timeoutMs: Long): Boolean = try {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("Type your number")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
    } catch (e: ComposeTimeoutException) {
        false
    }

    private fun exists(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, timeoutMs: Long = 15_000) = waitForAny(listOf(text), timeoutMs)

    private fun waitForAny(texts: List<String>, timeoutMs: Long) {
        try {
            compose.waitUntil(timeoutMs) { texts.any { exists(it) } }
        } catch (e: ComposeTimeoutException) {
            shot("failed-waiting")
            throw AssertionError("None of $texts appeared in ${timeoutMs / 1000}s", e)
        }
    }

    /** Taps the element whose text is exactly [text], or failing that the first that contains it. */
    private fun tap(text: String) {
        waitFor(text)
        val exact = compose.onAllNodes(hasText(text))
        if (exact.fetchSemanticsNodes().isNotEmpty()) exact.onFirst().performClick()
        else compose.onAllNodes(hasText(text, substring = true)).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun back() {
        val backs = compose.onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes()
        if (backs.isNotEmpty()) compose.onAllNodes(hasContentDescription("Back")).onFirst().performClick()
        else device.pressBack()
        compose.waitForIdle()
    }

    private fun field(): SemanticsNodeInteraction = compose.onAllNodes(hasSetTextAction() and isEnabled()).onFirst()

    private fun search(text: String) {
        val box = field()
        box.performTextClearance()
        if (text.isNotEmpty()) box.performTextInput(text)
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) = scrollToAny(listOf(text))

    private fun scrollToAny(texts: List<String>) {
        compose.waitUntil(30_000) {
            texts.any { exists(it) } || texts.any { t ->
                runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(t, substring = true)) }.isSuccess
            }
        }
    }

    /** Screenshots for the Test Lab report, in the app's own folder (pulled with --directories-to-pull). */
    private fun shot(name: String) = shotDevice(name)

    private fun shotDevice(name: String) {
        runCatching {
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens")
            dir.mkdirs()
            device.takeScreenshot(File(dir, "$name.png"))
        }
    }

    companion object {
        private const val PACKAGE = "app.foundwith"

        /** Before the app starts: permissions, as if the person tapped Allow, and a few contacts and calls. */
        @BeforeClass
        @JvmStatic
        fun preparePhone() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val ui = instrumentation.uiAutomation
            val perms = buildList {
                add(Manifest.permission.READ_CONTACTS)
                add(Manifest.permission.WRITE_CONTACTS)
                if (BuildConfig.CALL_LOG) {
                    add(Manifest.permission.READ_CALL_LOG)
                    add(Manifest.permission.WRITE_CALL_LOG)
                }
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            for (p in perms) runCatching { ui.grantRuntimePermission(PACKAGE, p) }
            val context = instrumentation.targetContext
            runCatching { addContact(context, "FW Test Plumber", "+91 90000 00090") }
            runCatching { addContact(context, "FW Test Clinic", "090000 00091") }
            runCatching { addContact(context, "FW Test Nobody", "9000000094") }
            if (BuildConfig.CALL_LOG) {
                val hourAgo = System.currentTimeMillis() - 3_600_000
                runCatching { addCall(context, "+919000000092", CallLog.Calls.MISSED_TYPE, hourAgo) }
                runCatching { addCall(context, "+919000000090", CallLog.Calls.OUTGOING_TYPE, hourAgo + 60_000) }
            }
        }

        private fun addContact(context: Context, name: String, number: String) {
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build(),
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }

        fun addCall(context: Context, number: String, type: Int, at: Long) {
            val values = ContentValues().apply {
                put(CallLog.Calls.NUMBER, number)
                put(CallLog.Calls.TYPE, type)
                put(CallLog.Calls.DATE, at)
                put(CallLog.Calls.DURATION, if (type == CallLog.Calls.MISSED_TYPE) 0 else 42)
                put(CallLog.Calls.NEW, 1)
            }
            context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
        }
    }
}
