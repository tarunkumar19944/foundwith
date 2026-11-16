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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
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
 * about what the app shows and does, not about exact counts. The numbers it saves (+91 90000 0007x)
 * are its own: the live test (tests/live) uses 0009x, so neither changes the other's counts.
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
        // A first start opens Android's own number picker by itself, over the app; the test closes it
        // and types the number, as someone would whose number isn't offered.
        device.wait(Until.hasObject(By.pkg("com.google.android.gms")), 8_000)
        if (device.currentPackageName != PACKAGE) {
            shotDevice("00-number-picker")
            device.pressBack()
            device.wait(Until.hasObject(By.pkg(PACKAGE)), 5_000)
        }
        shot("01-start")
        if (onHome(10_000)) return
        val code = args.getString("fwCode")
        assumeTrue("fwCode not given: can't sign in", !code.isNullOrBlank())
        if (!exists("Your mobile number")) {
            // Still on Welcome: Get started opens the picker again; closing it leads to typing the number.
            tap("Get started")
            device.wait(Until.hasObject(By.pkg("com.google.android.gms")), 8_000)
            if (device.currentPackageName != PACKAGE) {
                device.pressBack()
                device.wait(Until.hasObject(By.pkg(PACKAGE)), 5_000)
            }
        }
        waitFor("Your mobile number")
        field().performTextInput(args.getString("fwNumber") ?: "9000000010")
        shot("02-number")
        tap("Send code")
        waitFor("Enter the 6-digit code", 90_000)
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
            // The card can go by itself (another phone on this test account already took part).
            runCatching { compose.onAllNodes(hasText("Allow")).onFirst().performClick() }
        }
        waitFor("Updates", 120_000)
        // The notification and name cards wait until names have shown; put both off.
        repeat(2) { if (exists("Later")) runCatching { tap("Later") } }
        shot("06-ready")
    }

    @Test
    fun step03_contactsTabShowsTheSavedContacts() {
        waitFor("Contacts", 60_000)
        tap("Contacts")
        scrollTo("FW Test Plumber")
        shot("07-contacts")
        tap("FW Test Plumber")
        waitForAny(listOf("have this number", "has this number", "No one else on WhoHas has it yet", "No one on WhoHas has this number"), 30_000)
        shot("08-card")
        compose.onNode(hasContentDescription("More")).performClick()
        waitFor("Share number")
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
        waitFor("Type all 10 digits")
        search("9000000070")
        waitForAny(listOf("have it", "has it", "No one else on WhoHas has it yet", "No one on WhoHas has this number",
            "Today's searches are over"), 30_000)
        shot("11-search-number")
        search("")
        waitFor("Contacts")
    }

    @Test
    fun step05_callsTabShowsRecentCalls() {
        assumeTrue("contactsOnly build has no call log", BuildConfig.CALL_LOG)
        waitFor("Calls", 60_000)
        tap("Calls")
        scrollToAny(listOf("90000 00072", "FW Test Plumber"))
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
        if (note == null) {
            val seen = device.findObjects(By.textContains("")).mapNotNull { it.text }.filter { it.isNotBlank() }.take(40)
            device.pressBack()   // close the shade for the next steps
            // The shared test account can use up its daily call lookups when the tests run many
            // times in a day; that's the server's limit working, not the app failing.
            assumeTrue("the test account's call lookups for today are used up",
                !appLog().contains("after-call lookup failed: quota_exceeded"))
            throw AssertionError("No after-call notification for the missed call. On screen: $seen")
        }
        note!!.click()
        waitFor("Test Caller", 30_000)
        shot("14-caller-card")
        back()
    }

    @Test
    fun step07_settingsPagesOpen() {
        assertTrue(onHome(30_000))
        compose.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Your data")
        shot("15-settings")
        for ((row, inside) in listOf(
            "Notifications" to "Daily summary",
            "Your data" to "Download my data",
            "Help" to "How WhoHas works",
            "Invite friends" to "Share your link",
        )) {
            tap(row)
            waitFor(inside)
            shot("16-" + row.lowercase().replace(' ', '-'))
            back()
            waitFor("Your data")
        }
        tap("Your data")
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
        tap("Your data")
        tap("Sign out")
        waitFor("Sign out?")
        shot("16b-sign-out-dialog")
        // The dialog's own "Sign out" button, pressed through its click action (a tap on dialogs
        // misses on some emulators: their window offset isn't applied to injected taps).
        compose.onNode(hasText("Sign out") and hasClickAction() and hasAnyAncestor(isDialog()))
            .performSemanticsAction(SemanticsActions.OnClick)
        // Signed out: sign-in opens Android's number picker again, over the app. Compose only draws
        // while the test drives its clock, so wait with Compose, not with UiAutomator, until either
        // sign-in shows or the picker covers it.
        runCatching {
            compose.waitUntil(30_000) {
                exists("Get started") || exists("Your mobile number") || device.currentPackageName != PACKAGE
            }
        }
        shotDevice("17-signed-out")
        if (device.currentPackageName != PACKAGE) {
            device.pressBack()
            device.wait(Until.hasObject(By.pkg(PACKAGE)), 5_000)
        }
        waitForAny(listOf("Get started", "Your mobile number"), 30_000)
    }

    // ------------------------------------------------------------------ helpers

    private fun has(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    private fun onHome(timeoutMs: Long): Boolean = try {
        compose.waitUntil(timeoutMs) {
            has(hasContentDescription("Settings")) || has(hasText("Get started")) || has(hasText("Your mobile number"))
        }
        has(hasContentDescription("Settings"))
    } catch (e: ComposeTimeoutException) {
        false
    }

    /** False also while something else (a system sheet) is over the app. */
    private fun exists(text: String): Boolean = runCatching {
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

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

    /** This app's lines in the phone's log. */
    private fun appLog(): String = runCatching {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d -s FoundWithAfterCall")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
    }.getOrDefault("")

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
            runCatching { addContact(context, "FW Test Plumber", "+91 90000 00070") }
            runCatching { addContact(context, "FW Test Clinic", "090000 00071") }
            runCatching { addContact(context, "FW Test Nobody", "9000000074") }
            if (BuildConfig.CALL_LOG) {
                val hourAgo = System.currentTimeMillis() - 3_600_000
                runCatching { addCall(context, "+919000000072", CallLog.Calls.MISSED_TYPE, hourAgo) }
                runCatching { addCall(context, "+919000000070", CallLog.Calls.OUTGOING_TYPE, hourAgo + 60_000) }
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
