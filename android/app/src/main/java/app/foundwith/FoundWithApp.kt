package app.foundwith

import android.app.Application
import app.foundwith.data.Invites
import app.foundwith.work.Scheduling

class FoundWithApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifier.createChannels()
        // First start after install: was the app installed from a friend's invite link?
        Invites.captureReferrer(this, graph.store)
        // Keep background sync scheduled for people who finished setting up. Scheduling is
        // idempotent, so doing it on every start only repairs what an update may have cleared.
        if (graph.auth.isSignedIn && graph.store.syncEnabledHint) {
            Scheduling.scheduleAll(this)
        }
    }
}

val android.content.Context.graph: AppGraph
    get() = (applicationContext as FoundWithApp).graph
