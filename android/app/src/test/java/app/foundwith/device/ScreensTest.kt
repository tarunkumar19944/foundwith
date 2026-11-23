package app.foundwith.device

import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Star
import app.foundwith.data.InviteInfo
import app.foundwith.data.Circle
import app.foundwith.core.Levels
import app.foundwith.core.LinkJoins
import app.foundwith.ui.PeopleStack
import app.foundwith.ui.JoinFaces
import app.foundwith.ui.IconAvatar
import app.foundwith.ui.linkJoins
import app.foundwith.ui.LinkJoinNote
import app.foundwith.ui.GrowRow
import app.foundwith.ui.LevelProgress
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
import org.junit.Assert.assertEquals
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.Icons
import app.foundwith.data.SavedNumber
import app.foundwith.data.NumberLabel
import app.foundwith.data.NumberIndex
import app.foundwith.data.Holder
import app.foundwith.data.Card
import app.foundwith.data.CachedCards
import app.foundwith.data.AppState
import app.foundwith.core.KnownGain
import app.foundwith.core.CountChange
import app.foundwith.core.ChangeLog
import app.foundwith.core.CallType
import app.foundwith.core.CallEvent
import app.foundwith.core.UpdatesOrder
import app.foundwith.ui.SectionLabel
import app.foundwith.ui.isSaved
import app.foundwith.ui.marked
import app.foundwith.ui.updateItems
import app.foundwith.ui.ContactRow
import app.foundwith.ui.CallRow
import app.foundwith.ui.UpdateRow
import app.foundwith.ui.NewHeading
import app.foundwith.ui.TabBadge
import app.foundwith.ui.InGroup
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import app.foundwith.core.Level
import app.foundwith.core.NameSource
import app.foundwith.core.ShownName
import app.foundwith.ui.Avatar
import app.foundwith.ui.CodeBoxes
import app.foundwith.ui.CodeStatus
import app.foundwith.ui.CompareBox
import app.foundwith.ui.DeletedView
import app.foundwith.ui.EmptyHero
import app.foundwith.ui.FoundWithTheme
import app.foundwith.ui.HeroCard
import app.foundwith.ui.InfoRow
import app.foundwith.ui.LevelBlock
import app.foundwith.ui.LevelTag
import app.foundwith.ui.LevelUpBanner
import app.foundwith.core.DataNotice
import app.foundwith.BuildConfig
import app.foundwith.ui.AllowCard
import app.foundwith.ui.CardButtons
import app.foundwith.ui.YourNumberBar
import app.foundwith.ui.PrimaryButton
import app.foundwith.ui.CopyNumberButton
import app.foundwith.ui.NumberMenu
import app.foundwith.ui.WhCard
import app.foundwith.ui.rowTag
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the new pieces of the screens with the app's own theme and font, checks their words, and
 * saves each as a picture in build/screens (CI publishes them on the "screens" branch).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w390dp-h900dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            FoundWithTheme(dark = dark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
                }
            }
        }
        compose.waitForIdle()
        val root: View = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A phone where three numbers changed since Updates was last opened (Y, 23 Nov 2026). */
    private fun changedState(now: Long): AppState {
        val raju = "+919845011111"; val priya = "+919845022222"; val caller = "+919008011223"; val meena = "+919845033333"
        val refs = mapOf(raju to "r1", priya to "r2", caller to "r3", meena to "r4")
        return AppState(
            cards = CachedCards(savedAt = now, totalCards = 4, mine = Card("me", total = 23), cards = listOf(
                Card("r1", inContacts = true, total = 14, known = listOf(Holder("h1", "Priya Sharma", known = true)), knownCount = 1),
                Card("r2", inContacts = true, total = 3, knownCount = 0),
                Card("r3", inCallLog = true, total = 3, known = listOf(Holder("h2", "Kiran Sir", known = true)), knownCount = 1),
                Card("r4", inContacts = true, total = 6, knownCount = 0),
            )),
            index = NumberIndex(byRef = refs.entries.associate { it.value to NumberLabel(it.key) }, refByE164 = refs),
            contacts = listOf(SavedNumber("98450 11111", "Raju Plumber"), SavedNumber("98450 22222", "Priya Sharma"),
                SavedNumber("98450 33333", "Meena")),
            calls = listOf(CallEvent(7, "9008011223", CallType.MISSED, at = now - 40 * 60_000L)),
            changes = ChangeLog(
                changes = mapOf("r1" to CountChange("r1", 12, 14, now - 60 * 60_000L)),
                joined = mapOf("r2" to now - 30 * 60_000L),
                gains = mapOf("r3" to KnownGain("r3", listOf("h2"), now - 20 * 60_000L)),
            ),
            dotsSince = now - 2 * 60 * 60_000L,
            updatesSeenAt = now - 2 * 60 * 60_000L,
            loaded = true,
        )
    }

    @Test
    fun changesAreMarkedInEveryTabWithRedCounts() = changesMarked(dark = false)

    @Test
    fun changesAreMarkedInDarkModeToo() = changesMarked(dark = true)

    private fun changesMarked(dark: Boolean) {
        val now = System.currentTimeMillis()
        val state = changedState(now)
        val items = updateItems(state)
        val news = UpdatesOrder.newSince(items, state.dotsSince, state.seenNumbers)
        // Newest change first: the caller now known (20 min), Priya joined (30 min), Raju +2 (1 h).
        assertEquals(listOf("+919008011223", "+919845022222", "+919845011111"), news.map { it.e164 })
        assertEquals("+919845033333", items.last().e164)                 // nothing new: last
        val marks = state.marked(items)
        val inCalls = marks.keys.count { k -> state.calls.any { it.e164 == k } }
        val inContacts = marks.keys.count { state.isSaved(it) }
        assertEquals(1, inCalls)
        assertEquals(2, inContacts)

        shot(if (dark) "13-changes-marked-dark" else "12-changes-marked", dark = dark) {
            NewHeading(news.size)
            news.forEachIndexed { i, item -> InGroup(first = i == 0, last = i == news.lastIndex, tint = true) { UpdateRow(state, item, isNew = true) {} } }
            SectionLabel("Contacts")
            for (c in state.contacts) ContactRow(state, c, c.e164?.let { marks[it] }) {}
            SectionLabel("Call log")
            CallRow(state, state.calls[0], marks["+919008011223"]) {}
            NavigationBar {
                NavigationBarItem(selected = false, onClick = {}, label = { Text("Call log") },
                    icon = { TabBadge(inCalls) { Icon(Icons.Filled.Call, null) } })
                NavigationBarItem(selected = false, onClick = {}, label = { Text("Contacts") },
                    icon = { TabBadge(inContacts) { Icon(Icons.Filled.Person, null) } })
                NavigationBarItem(selected = true, onClick = {}, label = { Text("Updates") },
                    icon = { TabBadge(news.size) { Icon(Icons.Filled.Notifications, null) } })
            }
        }
        compose.onAllNodesWithText("2 more people have this number · now 14").assertCountEquals(2)   // Updates and Contacts
        compose.onAllNodesWithText("Now known to Kiran Sir").assertCountEquals(2)                     // Updates and Call log
        compose.onAllNodesWithText("Joined WhoHas").assertCountEquals(2)
        compose.onNodeWithText("6 people have it", substring = true).assertExists()                   // Meena: not marked

        // Opening Raju's card: he is no longer new anywhere.
        val seen = state.copy(seenNumbers = mapOf("+919845011111" to now))
        assertEquals(2, UpdatesOrder.newSince(updateItems(seen), seen.dotsSince, seen.seenNumbers).size)
        assertEquals(false, seen.marked(updateItems(seen)).containsKey("+919845011111"))
    }

    @Test
    fun theNumberBarShowsHowFarTheNextLevelIs() {
        // Y, 23 Nov 2026: a thin line under your number, "2 more → Popular" and Share; none at the top.
        var shared = 0
        shot("14-number-bar-next-level") {
            for ((total, up) in listOf(0 to 0, 3 to 1, 8 to 2, 23 to 1, 52 to 3)) {
                YourNumberBar(total = total, up = up, onClick = {}, onShare = { shared++ })
                Spacer(Modifier.height(8.dp))
            }
        }
        compose.onNodeWithText("10 more → Popular").assertExists()
        compose.onNodeWithText("2 more → Popular").assertExists()
        compose.onNodeWithText("27 more → Highly popular").assertExists()
        compose.onAllNodesWithText("Share").assertCountEquals(4)            // not at the top level
        compose.onAllNodesWithText("Share")[2].performClick()
        assertEquals(1, shared)
    }

    /** Ravi (a contact) and someone this phone doesn't know joined with your link; Ravi has your number. */
    private fun linkState(now: Long): AppState {
        val ravi = "+919845044444"
        return AppState(
            cards = CachedCards(savedAt = now, totalCards = 1,
                mine = Card("me", total = 9, known = listOf(Holder("h7", "Ravi Kumar", known = true, contactRef = "r7")), knownCount = 1),
                cards = listOf(Card("r7", inContacts = true, total = 4, knownCount = 0)),
                circle = Circle(memberRefs = listOf("r7"), invite = InviteInfo(code = "ABCDEFGH", joined = 2, joinedRefs = listOf("r7", "x9")))),
            index = NumberIndex(byRef = mapOf("r7" to NumberLabel(ravi)), refByE164 = mapOf(ravi to "r7")),
            contacts = listOf(SavedNumber("98450 44444", "Ravi Kumar")),
            changes = ChangeLog(changes = mapOf("me" to CountChange("me", 7, 9, now - 60_000L)),
                joined = mapOf("r7" to now - 10 * 60_000L), linkRefs = setOf("r7", "x9"),
                linked = mapOf("r7" to now - 10 * 60_000L, "x9" to now - 5 * 60_000L)),
            dotsSince = now - 60 * 60_000L,
            updatesSeenAt = now - 60 * 60_000L,
            loaded = true,
        )
    }

    @Test
    fun yourCardShowsWhatGrowsYourCount() {
        val state = linkState(System.currentTimeMillis())
        val joins = state.linkJoins()
        assertEquals(listOf("x9", "r7"), joins.map { it.ref })               // the latest noticed first
        assertEquals(listOf(null, "Ravi Kumar"), joins.map { it.name })
        assertEquals(listOf(false, true), joins.map { it.hasYours })
        shot("15-your-card-grow") {
            WhCard { LevelProgress(9) }
            WhCard(padding = 0.dp) {
                Column {
                    GrowRow(leading = { JoinFaces(joins) },
                        title = LinkJoins.title(joins.map { it.name }), line = LinkJoins.rowLine(joins.size, joins.count { it.hasYours }))
                    GrowRow(leading = { IconAvatar(Icons.Filled.Person, accent = true) }, title = LinkJoins.reach(38, true),
                        line = LinkJoins.REACH_LINE, onClick = {})
                    GrowRow(leading = { IconAvatar(Icons.Filled.Star, accent = true) }, title = "What Popular shows", line = Levels.tagNote(9)!!)
                }
            }
            WhCard { LevelProgress(52) }
        }
        compose.onNodeWithText("1 more person and you’re Popular").assertExists()
        compose.onNodeWithText("Ravi and 1 other joined with your link").assertExists()
        compose.onNodeWithText("1 of them has your number: +1").assertExists()
        compose.onNodeWithText("38 people you call aren’t on WhoHas").assertExists()
        compose.onNodeWithText("Top level. 52 people have your number").assertExists()
    }

    @Test
    fun updatesTellWhoJoinedWithYourLink() {
        val now = System.currentTimeMillis()
        val state = linkState(now)
        val items = updateItems(state)
        val news = UpdatesOrder.newSince(items, state.dotsSince, state.seenNumbers)
        assertEquals(listOf("+919845044444"), news.map { it.e164 })
        val strangers = state.linkJoins().filter { it.e164 == null }
        shot("16-link-joins-in-updates") {
            LinkJoinNote(strangers, 9) {}
            NewHeading(news.size)
            InGroup(first = true, last = true, tint = true) { UpdateRow(state, news[0], isNew = true) {} }
        }
        compose.onNodeWithText("Someone joined with your link").assertExists()
        compose.onNodeWithText("9 people have your number now").assertExists()
        compose.onNodeWithText("1 more and you’re Popular").assertExists()
        compose.onNodeWithText("Joined with your link · has your number").assertExists()
        compose.onNodeWithText("Share in group").assertExists()
    }

    @Test
    fun yourLevelOnHome() {
        shot("01-home-levels") {
            EmptyHero("+91 98450 31207")
            HeroCard("+91 98450 31207", 8, "people have it", "You know Priya and Meena", listOf("p" to "Priya", "m" to "Meena"),
                extra = { LevelBlock(8) })
            HeroCard("+91 98450 31207", 23, "people have it", "You know Priya, Meena and 4 more", extra = { LevelBlock(23) })
        }
        compose.onNodeWithText("No one on WhoHas has your number yet").assertExists()
        compose.onAllNodesWithText("Share WhoHas so people you know can find you.").assertCountEquals(2)
        compose.onNodeWithText("More people means more callers you can recognise.").assertExists()
        compose.onAllNodesWithText("Share in group").assertCountEquals(0)          // no link inside the card (Y, 22 Nov)
    }

    @Test
    fun allowCardSaysWhatIsSent() {
        shot("10-allow-notice") { AllowCard(onAllow = {}, onHowItWorks = {}) }
        compose.onNodeWithText(DataNotice.text(BuildConfig.CALL_LOG)).assertExists()
        compose.onNodeWithText("Agree and allow").assertExists()
    }

    @Test
    fun yourNumberAboveTheTabs() {
        shot("09-your-number-bar") {
            YourNumberBar(total = 23, up = 1, onClick = {})
            YourNumberBar(total = 1, up = 0, onClick = {})
            YourNumberBar(total = 0, up = 0, onClick = {})
        }
        compose.onNodeWithText("+1").assertExists()
        compose.onNodeWithText("No one on WhoHas has your number yet").assertExists()
        compose.onNodeWithText("Popular").assertExists()
        compose.onAllNodesWithText("Not popular").assertCountEquals(2)
    }

    @Test
    fun darkModeLooksTheSame() {
        // The phone in dark mode: Facebook-style dark grey, same layout (Y, 23 Nov 2026).
        shot("11-dark-mode", dark = true) {
            YourNumberBar(total = 23, up = 1, onClick = {})
            WhCard { CompareBox(Level.POPULAR, 23) }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardButtons("+917760934418", callBack = true)
                PrimaryButton("Agree and allow", {})
            }
        }
        compose.onNodeWithText("Agree and allow").assertExists()
    }

    @Test
    fun beforeAllowAndAtTheTop() {
        shot("02-home-before-allow-and-top") {
            HeroCard("+91 98450 31207", 8, "people have it", "Allow contacts to see who they are.", extra = { LevelBlock(8) })
            HeroCard("+91 98450 31207", 52, "people have it", "You know Priya, Meena and 12 more", extra = { LevelBlock(52) })
        }
        compose.onNodeWithText("Highly popular").assertExists()
    }

    @Test
    fun theBannerTheDayYouReachALevel() {
        shot("03-level-up-banner") { LevelUpBanner(10) {} }
        compose.onNodeWithText("You’re Popular now").assertExists()
        compose.onNodeWithText("10 people have your number. 40 more and you’re Highly popular.").assertExists()
    }

    @Test
    fun aCardComparesWithYourNumber() {
        shot("04-card-compare-and-buttons") {
            WhCard { CompareBox(Level.HIGHLY_POPULAR, 8) }
            WhCard { CompareBox(Level.POPULAR, 23) }
            WhCard { CompareBox(Level.NOT_POPULAR, 23) }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("+91 77609 34418", style = MaterialTheme.typography.headlineSmall)
                    CopyNumberButton("+917760934418")
                }
                CardButtons("+917760934418", callBack = true)
                CardButtons("+917760934418", callBack = false)
            }
        }
        compose.onNodeWithText("More popular than your number. 2 more people and you catch up.").assertExists()
        compose.onNodeWithText("Same level as your number.").assertExists()
        compose.onNodeWithText("Your number is ahead of this one.").assertExists()
        compose.onNodeWithContentDescription("Copy number").assertExists()
        compose.onAllNodesWithText("Truecaller").assertCountEquals(2)
        compose.onAllNodesWithText("Call back").assertCountEquals(1)
        compose.onAllNodesWithText("Ask who this is").assertCountEquals(0)
    }

    @Test
    fun rowsTagOnlyPopularNumbers() {
        shot("05-rows-and-tags") {
            WhCard(padding = 4.dp) {
                InfoRow(leading = { Avatar("+919845011223", "Meena") }, name = ShownName("Meena", NameSource.CONTACT), sub = "In your contacts",
                    line = "11 people have it · You know Priya, Deepa and 3 more", tag = rowTag(11), onClick = {}, e164 = "+919845011223")
                InfoRow(leading = { Avatar("+918095066231", "Mahesh Electricals") }, name = ShownName("Mahesh Electricals", NameSource.POPULAR),
                    line = "64 people have it", tag = rowTag(64), onClick = {}, e164 = "+918095066231")
                InfoRow(leading = { Avatar("+919731120456", "Arun") }, name = ShownName("Arun", NameSource.CONTACT),
                    line = "No one else on WhoHas has it yet", tag = rowTag(0), onClick = {}, e164 = "+919731120456")
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("All three levels", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LevelTag(Level.NOT_POPULAR); LevelTag(Level.POPULAR); LevelTag(Level.HIGHLY_POPULAR)
            }
        }
        compose.onAllNodesWithText("Highly popular").assertCountEquals(2)
        compose.onNodeWithText("Mahesh Electricals").assertExists()       // the name is never squeezed out by a tag
    }

    @Test
    fun pressAndHoldMenu() {
        shot("06-press-and-hold-menu") {
            WhCard { NumberMenu("+917760934418", "+91 77609 34418", onOpen = {}, onDismiss = {}) }
        }
        compose.onNodeWithText("Copy number").assertExists()
        compose.onNodeWithText("Search in Truecaller").assertExists()
        compose.onNodeWithText("Call back").assertExists()
        compose.onNodeWithText("Open card").assertExists()
    }

    @Test
    fun accountDeleted() {
        shot("07-account-deleted") { Column(Modifier.padding(horizontal = 24.dp)) { DeletedView {} } }
        compose.onNodeWithText("Your account is deleted").assertExists()
        compose.onNodeWithText("Your number, name, contacts and call log numbers are gone from WhoHas.").assertExists()
    }

    @Test
    fun signInCode() {
        shot("08-sign-in-code") {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Enter the code", style = MaterialTheme.typography.headlineSmall)
                CodeBoxes("", enabled = true, error = false) {}
                CodeStatus(signingIn = false, error = null, checkMessages = false)
                CodeBoxes("482913", enabled = false, error = false) {}
                CodeStatus(signingIn = true, error = null, checkMessages = false)
                CodeBoxes("", enabled = true, error = false) {}
                CodeStatus(signingIn = false, error = null, checkMessages = true)
                CodeBoxes("", enabled = true, error = true) {}
                CodeStatus(signingIn = false, error = "Wrong code. Check the SMS and try again.", checkMessages = false)
            }
        }
        compose.onNodeWithText("Check your messages for the code").assertExists()
        compose.onNodeWithText("Waiting for the SMS. The code fills in by itself.").assertExists()
        compose.onNodeWithText("Signing you in…").assertExists()
    }
}
