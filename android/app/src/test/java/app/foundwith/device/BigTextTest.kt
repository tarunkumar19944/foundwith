package app.foundwith.device

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import app.foundwith.core.CallEvent
import app.foundwith.core.CallType
import app.foundwith.core.ChangeLog
import app.foundwith.core.CountChange
import app.foundwith.core.Level
import app.foundwith.data.AppState
import app.foundwith.data.CachedCards
import app.foundwith.data.Card
import app.foundwith.data.Holder
import app.foundwith.data.NumberIndex
import app.foundwith.data.NumberLabel
import app.foundwith.data.SavedNumber
import app.foundwith.ui.CallBackLine
import app.foundwith.ui.CallRow
import app.foundwith.ui.CompareBox
import app.foundwith.ui.ContactRow
import app.foundwith.ui.FoundWithTheme
import app.foundwith.ui.LevelUpBanner
import app.foundwith.ui.ToggleRow
import app.foundwith.ui.UpdateRow
import app.foundwith.ui.WhCard
import app.foundwith.ui.YourNumberBar
import app.foundwith.ui.updateItems
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Y, 24 Nov 2026, "would all these fit to all phones?": the busiest rows on a small phone (320 dp
 * wide, like a realme C11 or Galaxy A10 with display size up), at 130% and 200% text. The words
 * must still be there, and the pictures (big-*.png) show nothing is cut off or overlapping.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h2400dp-xhdpi")
class BigTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            FoundWithTheme(dark = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
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

    private fun state(now: Long): AppState {
        val vijaya = "+919845011111"; val caller = "+917760934418"
        val refs = mapOf(vijaya to "r1", caller to "r2")
        return AppState(
            cards = CachedCards(savedAt = now, totalCards = 2, mine = Card("me", total = 8), cards = listOf(
                Card("r1", inContacts = true, total = 14, known = listOf(Holder("h1", "Priya Sharma", known = true),
                    Holder("h2", "Venkatesh Murthy", known = true)), knownCount = 2),
                Card("r2", inCallLog = true, total = 31, known = listOf(Holder("h3", "Kiran Sir", known = true)), knownCount = 1),
            )),
            index = NumberIndex(byRef = refs.entries.associate { it.value to NumberLabel(it.key) }, refByE164 = refs),
            contacts = listOf(SavedNumber("98450 11111", "Vijaya Lakshmi Narasimhamurthy")),
            calls = listOf(CallEvent(7, "7760934418", CallType.MISSED, at = now - 40 * 60_000L),
                CallEvent(8, "9845011111", CallType.OUTGOING, at = now - 90 * 60_000L)),
            changes = ChangeLog(changes = mapOf("r1" to CountChange("r1", 12, 14, now - 60 * 60_000L))),
            dotsSince = now - 2 * 60 * 60_000L,
            updatesSeenAt = now - 2 * 60 * 60_000L,
            loaded = true,
        )
    }

    private fun busyRows(name: String, scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val now = System.currentTimeMillis()
        val s = state(now)
        val items = updateItems(s)
        shot(name) {
            YourNumberBar(total = 8, up = 2, onClick = {}, onShare = {})
            LevelUpBanner(10) {}
            CallRow(s, s.calls[0], null) {}
            CallRow(s, s.calls[1], null) {}
            ContactRow(s, s.contacts[0], null) {}
            for (item in items) UpdateRow(s, item, isNew = true) {}
            WhCard { CompareBox(Level.HIGHLY_POPULAR, 8) }
            Column(Modifier.padding(horizontal = 16.dp)) { CallBackLine(s.calls[0], "+917760934418") }
            ToggleRow("Tell me after every call", "Calls with nothing new arrive quietly, in the notification panel.", true) {}
        }
        compose.onNodeWithText("Vijaya Lakshmi Narasimhamurthy", substring = true, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Truecaller", useUnmergedTree = true).assertCountEquals(2)   // the two calls from a stranger
        compose.onNodeWithText("Call", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Tell me after every call", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Share", useUnmergedTree = true).assertExists()
    }

    @Test fun busyRowsAt130PercentText() = busyRows("big-130-small-phone", 1.3f)

    @Test fun busyRowsAt200PercentText() = busyRows("big-200-small-phone", 2.0f)
}
