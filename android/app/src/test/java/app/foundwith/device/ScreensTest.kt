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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import app.foundwith.ui.NumberButtons
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

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            FoundWithTheme {
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
            Column(Modifier.padding(horizontal = 16.dp)) { NumberButtons("+917760934418") }
        }
        compose.onNodeWithText("More popular than your number. 2 more people and you catch up.").assertExists()
        compose.onNodeWithText("Same level as your number.").assertExists()
        compose.onNodeWithText("Your number is ahead of this one.").assertExists()
        compose.onNodeWithText("Copy number").assertExists()
        compose.onNodeWithText("Truecaller").assertExists()
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
