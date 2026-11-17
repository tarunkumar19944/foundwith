package app.foundwith

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
            Notifier.ACTION_OPEN_NUMBER -> openNumber.value = intent.getStringExtra(Notifier.EXTRA_NUMBER)
            Notifier.ACTION_OPEN_UPDATES -> openNumber.value = UPDATES
        }
    }

    override fun onResume() {
        super.onResume()
        if (!graph.auth.isSignedIn) return
        // Repairs background work if Android or an update cleared it. Cheap and idempotent.
        if (graph.store.syncEnabledHint) Scheduling.scheduleAll(this)
        // Catch up on calls the phone didn't let WhoHas check, and on anything new.
        AfterCall.runNow(this)
        lifecycleScope.launch {
            graph.model.reloadPhone()
            graph.model.refresh()
        }
    }

    override fun onStop() {
        super.onStop()
        if (graph.auth.isSignedIn) graph.model.leftApp()
    }
}
