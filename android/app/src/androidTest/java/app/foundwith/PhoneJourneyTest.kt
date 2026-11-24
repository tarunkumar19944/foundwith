package app.foundwith

import kotlinx.coroutines.runBlocking
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import android.service.notification.StatusBarNotification

import android.app.NotificationManager

import android.app.Notification

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
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
 *   fwCaller  a number someone on WhoHas has, not in this phone's contacts (default +919000000077,
 *             which the live server's "Test Caller" account has: tests/live/phone_fixture.py)
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
            // The card can go by itself (another phone on this test account already took part).
            // On tall-text phones (a Galaxy on Android 16) the button sits below the screen: scroll first.
            runCatching { compose.onAllNodes(hasText("Agree and allow")).onFirst().performScrollTo().performClick() }
        }
        waitFor("Updates", 120_000)
        // The notification and name cards wait until names have shown; put both off.
        repeat(2) { if (exists("Later")) runCatching { tap("Later") } }
        // Your number sits above the tabs, the same on every tab (Y, 22 Nov).
        waitForAny(listOf("have your number", "has your number", "No one on WhoHas has your number yet"), 30_000)
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
        compose.onNode(hasContentDescription("More")).performSemanticsAction(SemanticsActions.OnClick)
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
        search("98")
        waitFor("Type the number")
        // Every kind of number (23 Nov 2026): a short number waits for the Search button, then is looked up.
        search("56161")
        waitFor("Search")
        assertTrue("a short number was looked up before Search was tapped",
            !exists("No one on WhoHas has this number") && !exists("Checking 56161"))
        compose.onAllNodes(hasText("Search") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        waitForAny(listOf("have it", "has it", "No one else on WhoHas has it yet", "No one on WhoHas has this number",
            "Today's searches are over"), 30_000)
        shot("10b-search-short-number")
        search("9000000070")
        waitForAny(listOf("have it", "has it", "No one else on WhoHas has it yet", "No one on WhoHas has this number",
            "Today's searches are over"), 30_000)
        shot("11-search-number")
        // Back closes the search, never the app (Y, 17 Nov): once for the keyboard, once for the search.
        // Some real phones show no keyboard for typed-in test text, so the first back already closes it.
        // A real tap on the box first: on a Samsung tablet the taskbar kept the key focus after the
        // test opened the app, so Back went to the launcher, not to WhoHas (gta9pwifi, 23 Nov).
        touch(field())
        device.pressBack()
        compose.waitForIdle()
        val stillSearching = runCatching {
            val config = field().fetchSemanticsNode().config
            val text = androidx.compose.ui.semantics.SemanticsProperties.EditableText
            config.contains(text) && config[text].text.isNotEmpty()
        }.getOrDefault(false)
        if (stillSearching) device.pressBack()
        assertTrue("Back closed the app instead of the search",
            device.wait(Until.hasObject(By.pkg(PACKAGE)), 5_000) && onHome(10_000))
        search("")
        waitFor("Contacts")
    }

    @Test
    fun step05_callsTabShowsRecentCalls() {
        assumeTrue("contactsOnly build has no call log", BuildConfig.CALL_LOG)
        waitFor("Call log", 60_000)
        tap("Call log")
        scrollToAny(listOf("90000 00072", "FW Test Plumber"))
        shot("12-calls")
    }

    @Test
    fun step06_aMissedCallFromANumberSomeoneHasIsNotified() {
        // Since 22 Nov 2026 a call is told only when someone on WhoHas has the number, so the
        // caller is a number "Test Caller" has; a name alone (with nobody having it) stays silent.
        assumeTrue("contactsOnly build has no call log", BuildConfig.CALL_LOG)
        val caller = args.getString("fwCaller") ?: "+919000000077"
        addCall(InstrumentationRegistry.getInstrumentation().targetContext, caller, CallLog.Calls.MISSED_TYPE, System.currentTimeMillis())
        // Coming back to the app checks calls at once (the call-log trigger would too, a little later).
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // Look for it in the app's own posted notifications: reading the shade's text depends on
        // the phone (Android 8 doesn't always expose it; One UI 7 covers it with a tutorial once).
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(NotificationManager::class.java)
        fun posted(): StatusBarNotification? = manager.activeNotifications.firstOrNull { sbn ->
            val extras = sbn.notification.extras
            val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                .joinToString(" ") { extras.getCharSequence(it)?.toString().orEmpty() }
            caller.takeLast(5) in text
        }
        var note: StatusBarNotification? = null
        val until = System.currentTimeMillis() + 120_000
        while (note == null && System.currentTimeMillis() < until) {
            note = posted()
            if (note == null) Thread.sleep(1_000)
        }
        device.openNotification()
        device.waitForIdle(2_000)
        shotDevice("13-notification")
        // Close the shade (and One UI's one-time tutorial) for the next steps, but only while it is
        // open: on a Samsung tablet it closed by itself, and Back then closed WhoHas (gta9pwifi, 23 Nov).
        closeShade()
        if (note == null) {
            // The shared test account can use up its daily call lookups when the tests run many
            // times in a day; that's the server's limit working, not the app failing.
            assumeTrue("the test account's call lookups for today are used up",
                !appLog().contains("after-call lookup failed: quota_exceeded"))
            throw AssertionError("No after-call notification for the missed call. Posted: " +
                manager.activeNotifications.map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) })
        }
        val text = note.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("The note should say who has the number: $text", text.contains("on WhoHas") || text.startsWith("Known to"))
        val buttons = note.notification.actions.orEmpty().map { it.title.toString() }
        assertTrue("Copy number and Truecaller buttons: $buttons", buttons == listOf("Copy number", "Truecaller"))
        // Tapping it opens the caller's card.
        note.notification.contentIntent.send()
        // The caller's card is open once its copy icon shows (only a card has one beside the number).
        try {
            compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Copy number")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            shot("failed-waiting")
            throw AssertionError("The caller's card didn't open from the notification", e)
        }
        // It shows the notification's count straight away, never "No one has this number" first.
        assertFalse("The card said no one has the number, but the notification said someone does",
            exists("No one on WhoHas has this number yet"))
        waitForAny(listOf("person has this number", "people have this number"), 20_000)
        compose.waitForIdle()
        device.waitForIdle(2_000)   // let the screen finish moving to the card before the picture
        Thread.sleep(800)
        // Call sits on the card's call line (24 Nov 2026); the card has no Truecaller button any more.
        waitFor("Call")
        assertTrue("The card has no Truecaller button now", !exists("Truecaller"))
        shot("14-caller-card")
        back()
        assertAppOnScreen()
    }

    @Test
    fun step06b_yourOwnCardShowsHowToGrowYourCount() {
        // 23 Nov 2026: the bar above the tabs opens your own card, with how far the next level is,
        // what grows your count, Share in group and Invite friends.
        assertTrue(onHome(30_000))
        assertAppOnScreen()
        // The bar shows once Home has loaded (Settings is there a moment earlier).
        val bar = hasText("your number", substring = true) and hasClickAction()
        try {
            compose.waitUntil(30_000) { has(bar) }
        } catch (e: ComposeTimeoutException) {
            shot("failed-waiting")
            throw AssertionError("The number bar didn't appear on Home in 30s")
        }
        compose.onAllNodes(bar).onFirst().performClick()
        compose.waitForIdle()
        waitForAny(listOf("you’re Popular", "you’re Highly popular", "Top level"), 20_000)
        waitFor("Invite friends")
        compose.waitForIdle()
        device.waitForIdle(2_000)   // let the screen finish moving to the card before the picture
        Thread.sleep(800)
        assertAppOnScreen()
        shot("14b-your-card")
        back()
        assertTrue(onHome(10_000))
    }

    /**
     * Someone joins with this phone's invite link (23 Nov 2026), end to end on the live server.
     * Only when asked (fwJoiner, fwJoinerCode and fwKey given): a number can join through a link
     * once, ever, so each joiner number works once. What Play does at install (hand the app the
     * link's code) isn't covered; the joiner redeems the code itself, as the app does next.
     */
    @Test
    fun step06c_someoneJoinsWithYourLink() {
        val joiner = args.getString("fwJoiner")
        val joinerCode = args.getString("fwJoinerCode")
        val key = args.getString("fwKey")
        assumeTrue("Runs only when asked: a number joins through a link once, ever",
            !joiner.isNullOrBlank() && !joinerCode.isNullOrBlank() && !key.isNullOrBlank())
        assertTrue(onHome(30_000))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as FoundWithApp).graph
        // The joiner is in this phone's contacts, so the join shows by name; the phone sends its
        // contacts and takes the counts as they are now (the joiner isn't on WhoHas yet).
        addContact(context, "FW Test Joiner", joiner!!)
        runBlocking {
            graph.sync.fullSync()
            graph.model.reloadPhone()
            graph.model.refresh()
        }
        val code = runBlocking { graph.api.circle().invite.code }
        assertTrue("This phone has no invite code", code.isNotBlank())

        // WhoHas in the background, as when a friend joins later: then it tells with a notification.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        var token = firebaseSignIn(key!!, joiner, joinerCode!!)
        if (api(token, "GET", "/v1/me").first == 200) {
            api(token, "DELETE", "/v1/me")
            token = firebaseSignIn(key, joiner, joinerCode)   // a sign-in newer than the deletion
        }
        val (made, me) = api(token, "POST", "/v1/me", JSONObject().put("display_name", "Test Joiner"))
        assertTrue("The joiner's account: $made $me", made == 200)
        val consent = me.optJSONObject("sync")?.optString("current_consent_version")
        api(token, "PUT", "/v1/me/sync", JSONObject().put("enabled", true).put("consent_version", consent))
        val phone = graph.model.state.value.profile?.phone ?: "+91" + (args.getString("fwNumber") ?: "9000000010")
        api(token, "PUT", "/v1/me/numbers", JSONObject().put("contacts", JSONArray().put(phone)).put("call_log", JSONArray()))
        val (redeemed, body) = api(token, "POST", "/v1/me/invite/redeem", JSONObject().put("code", code))
        if (redeemed == 409 && body.optJSONObject("error")?.optJSONObject("details")?.optString("reason") == "already_used") {
            api(token, "DELETE", "/v1/me")
            assumeTrue("$joiner has already joined through a link once (that counts once, ever)", false)
        }
        assertTrue("Joining with the link: $redeemed $body", redeemed == 200)

        try {
            // The server pushes the news; the app also checks now, as it does on a push.
            runBlocking { graph.model.refresh() }
            val manager = context.getSystemService(NotificationManager::class.java)
            fun note() = manager.activeNotifications.firstOrNull {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.endsWith("joined with your link") == true
            }
            val until = System.currentTimeMillis() + 60_000
            while (note() == null && System.currentTimeMillis() < until) Thread.sleep(1_000)
            val n = note() ?: throw AssertionError("No \"joined with your link\" notification. Posted: " +
                manager.activeNotifications.map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) })
            val title = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            val text = n.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            assertEquals("FW Test Joiner joined with your link", title)
            assertTrue("The note says your count: $text", text.contains("your number") || text.contains("have it now"))
            assertEquals(listOf("Share in group"), n.notification.actions.orEmpty().map { it.title.toString() })
            device.openNotification()
            device.waitForIdle(2_000)
            shotDevice("14c-link-join-notification")
            closeShade()

            // Back in the app: your own card names the join, and so does Updates.
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(onHome(30_000))
            compose.waitUntil(30_000) { has(hasText("your number", substring = true) and hasClickAction()) }
            compose.onAllNodes(hasText("your number", substring = true) and hasClickAction()).onFirst().performClick()
            waitFor("joined with your link", 30_000)
            compose.waitForIdle(); device.waitForIdle(2_000); Thread.sleep(800)
            assertAppOnScreen()
            shot("14d-your-card-link-join")
            back()
            assertTrue(onHome(30_000))
            tap("Updates")
            try {
                waitFor("Joined with your link", 30_000)
            } finally {
                compose.waitForIdle(); device.waitForIdle(2_000); Thread.sleep(800)
                shot("14e-updates-link-join")
            }
            assertAppOnScreen()
        } finally {
            // The joiner's account goes; the join stays recorded (once per number, ever).
            runCatching { api(token, "DELETE", "/v1/me") }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    /** Identity Platform's REST sign-in for a test number (no SMS, the fixed code), as the live test does. */
    private fun firebaseSignIn(key: String, phone: String, code: String): String {
        val api = "https://identitytoolkit.googleapis.com/v1/accounts"
        val (s1, sent) = http("POST", "$api:sendVerificationCode?key=$key", null, JSONObject().put("phoneNumber", phone))
        assertTrue("sendVerificationCode: $s1 $sent", s1 == 200)
        val (s2, signed) = http("POST", "$api:signInWithPhoneNumber?key=$key", null,
            JSONObject().put("sessionInfo", sent.getString("sessionInfo")).put("code", code))
        assertTrue("signInWithPhoneNumber: $s2 $signed", s2 == 200)
        return signed.getString("idToken")
    }

    /** A call to the WhoHas server as the joiner. */
    private fun api(token: String, method: String, path: String, body: JSONObject? = null) =
        http(method, BuildConfig.API_BASE_URL.trimEnd('/') + path, token, body)

    private fun http(method: String, url: String, token: String?, body: JSONObject?): Pair<Int, JSONObject> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return status to runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    @Test
    fun step07_settingsPagesOpen() {
        assertTrue(onHome(30_000))
        assertAppOnScreen()
        compose.onNode(hasContentDescription("Settings")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Your data")
        shot("15-settings")
        for ((row, inside) in listOf(
            "Notifications" to "Changes to your numbers",
            "Your data" to "Download my data",
            "Help" to "How WhoHas works",
            "Invite friends" to "Share in group",
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
        compose.onNode(hasContentDescription("Settings")).performSemanticsAction(SemanticsActions.OnClick)
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
    /**
     * Closes the notification shade, and One UI's one-time "Notifications / Quick settings" tip
     * over it (its OK), pressing Back only while the shade is in front (23 Nov 2026: on Galaxy
     * A14 5G, A16 and Z Flip6 one Back closed only the tip, and the shade covered the later steps).
     */
    private fun closeShade() {
        repeat(4) {
            if (device.currentPackageName != "com.android.systemui") return
            val ok = device.findObject(By.pkg("com.android.systemui").text("OK"))
            if (ok != null) ok.click() else device.pressBack()
            device.waitForIdle(1_000)
            Thread.sleep(500)
        }
    }

    /**
     * WhoHas is what's on the screen, not covered by the shade: Compose's checks read the app's own
     * screen tree, so they pass even under the shade; this makes such a step fail instead.
     */
    private fun assertAppOnScreen() {
        val until = System.currentTimeMillis() + 10_000
        while (device.currentPackageName != PACKAGE && System.currentTimeMillis() < until) {
            closeShade()
            Thread.sleep(500)
        }
        assertEquals("WhoHas isn't the app on screen", PACKAGE, device.currentPackageName)
    }

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
        // Through the click action when there is one (24 Nov 2026): with large text a row can sit at
        // the edge of the screen, where an injected tap lands on the bar below it instead.
        for (matcher in listOf(hasText(text), hasText(text, substring = true))) {
            val clickable = compose.onAllNodes(matcher and hasClickAction())
            if (clickable.fetchSemanticsNodes().isNotEmpty()) {
                clickable.onFirst().performSemanticsAction(SemanticsActions.OnClick)
                compose.waitForIdle()
                return
            }
        }
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

    /** A real tap through Android (Compose's own clicks don't move the window focus). */
    private fun touch(node: SemanticsNodeInteraction) {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val at = IntArray(2)
        compose.runOnIdle { compose.activity.window.decorView.getLocationOnScreen(at) }
        device.click((at[0] + bounds.center.x).toInt(), (at[1] + bounds.center.y).toInt())
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
        private const val PACKAGE = "app.whohas"

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
                    add(Manifest.permission.READ_PHONE_STATE)
                }
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            for (p in perms) runCatching { ui.grantRuntimePermission(PACKAGE, p) }
            // Xiaomi (MIUI / HyperOS) refuses to let the test open the app's screen: the test starts it
            // from the app's own process, which counts as a start from the background (log: "MIUILOG-
            // Permission Denied Activity"), and the test then waits until Test Lab's time runs out. A
            // person opening the app from the home screen or a notification isn't affected. Xiaomi's own
            // app-op 10021 ("open new windows while running in the background") allows it.
            // Large text runs (fwBig=1.3 or 2, 24 Nov 2026): the font scale, and the display size one step up.
            InstrumentationRegistry.getArguments().getString("fwBig")?.toFloatOrNull()?.let { scale ->
                shell(ui, "settings put system font_scale $scale")
                val physical = Regex("Physical density: (\\d+)").find(shell(ui, "wm density"))?.groupValues?.get(1)?.toIntOrNull()
                if (physical != null) shell(ui, "wm density ${(physical * 1.15f).toInt()}")
            }
            if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
                runCatching {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        ui.executeShellCommand("appops set $PACKAGE 10021 allow")
                    ).use { it.readBytes() }
                }
            }
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

        private fun shell(ui: android.app.UiAutomation, command: String): String = runCatching {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { String(it.readBytes()) }
        }.getOrDefault("")

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
