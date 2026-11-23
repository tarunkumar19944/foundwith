package app.foundwith

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.foundwith.call.AfterCall
import app.foundwith.call.Notifier
import app.foundwith.ui.AppRoot
import app.foundwith.ui.FoundWithTheme
import app.foundwith.ui.UPDATES
import app.foundwith.work.Scheduling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A number to open, from a tapped notification. */
    private val openNumber = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always charcoal, so the status and navigation bars always get light icons.
        // The opening screen stays until the saved counts, contacts and call log are read, so Home
        // opens as it is, never as a first-time screen for a moment. At most 3 seconds.
        val opened = SystemClock.uptimeMillis()
        installSplashScreen().setKeepOnScreenCondition {
            graph.auth.isSignedIn && !graph.model.state.value.loaded && SystemClock.uptimeMillis() - opened < MAX_OPENING_MS
        }
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        handle(intent)
        lifecycleScope.launch { graph.model.load() }
        setContent {
            FoundWithTheme {
                AppRoot(graph, openNumber)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            // Only WhoHas's own notifications carry the key (this activity is exported as the launcher).
            Notifier.ACTION_OPEN_NUMBER -> if (intent.getStringExtra(Notifier.EXTRA_KEY) == graph.store.openKey) {
                openNumber.value = intent.getStringExtra(Notifier.EXTRA_NUMBER)
            }
            Notifier.ACTION_OPEN_UPDATES -> openNumber.value = UPDATES
        }
    }

    override fun onStart() {
        super.onStart()
        graph.inFront = true
    }

    override fun onResume() {
        super.onResume()
        if (!graph.auth.isSignedIn) return
        // Repairs background work if Android or an update cleared it. Cheap and idempotent.
        if (graph.store.syncEnabledHint) Scheduling.scheduleAll(this)
        // Contacts and call log both turned off since last time: clear them from the server now.
        if (graph.store.syncEnabledHint && !graph.book.hasContactsPermission() && !graph.book.hasCallLogPermission()) {
            Scheduling.syncNow(this)
        }
        // Catch up on calls the phone didn't let WhoHas check, and on anything new.
        AfterCall.runNow(this)
        lifecycleScope.launch {
            graph.model.reloadPhone()
            graph.model.refresh()
        }
    }

    override fun onStop() {
        super.onStop()
        graph.inFront = false
        if (graph.auth.isSignedIn) graph.model.leftApp()
    }
}

/** The opening screen never waits longer than this for the saved data. */
private const val MAX_OPENING_MS = 3_000L
