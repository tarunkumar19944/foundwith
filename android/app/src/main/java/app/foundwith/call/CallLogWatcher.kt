package app.foundwith.call

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import app.foundwith.BuildConfig
import app.foundwith.graph
import kotlinx.coroutines.launch

/**
 * While WhoHas's process is running, it watches the call log itself (23 Nov 2026), so a call is
 * checked about half a second after it ends, without waiting for Android to start a background
 * job. No extra permission: the call log permission covers it. When Android has stopped the
 * process, the call-log trigger in [AfterCall.watch] still wakes it.
 */
object CallLogWatcher {
    /** Lets the call log settle (some phones write a call in two steps). */
    private const val SETTLE_MS = 400L

    @Volatile private var observer: ContentObserver? = null

    fun start(context: Context) {
        if (!BuildConfig.CALL_LOG || observer != null) return
        val app = context.applicationContext
        val graph = app.graph
        if (!graph.auth.isSignedIn || !graph.store.syncEnabledHint || !graph.book.hasCallLogPermission()) return
        val handler = Handler(Looper.getMainLooper())
        val check = Runnable {
            graph.scope.launch {
                runCatching { AfterCall.checkUntilDone(app) }.onFailure { Log.w(TAG, "check failed", it) }
            }
        }
        val watcher = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(check)
                handler.postDelayed(check, SETTLE_MS)
            }
        }
        synchronized(this) {
            if (observer != null) return
            observer = watcher
        }
        runCatching { app.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, watcher) }
            .onFailure { Log.w(TAG, "can't watch the call log", it); observer = null }
    }

    fun stop(context: Context) {
        val watcher = synchronized(this) { observer.also { observer = null } } ?: return
        runCatching { context.applicationContext.contentResolver.unregisterContentObserver(watcher) }
    }

    private const val TAG = "FoundWithAfterCall"
}
