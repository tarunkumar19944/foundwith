package app.foundwith.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import app.foundwith.BuildConfig
import app.foundwith.graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Call ended" from Android itself (Y, 24 Nov 2026: notifications for missed and dialled calls came
 * minutes late on a Pixel). Android delivers the phone's state to this receiver at once even when
 * WhoHas is closed, unlike the call-log job, which Android may hold back for apps not used often.
 * Needs the Phone permission (READ_PHONE_STATE); without it this never runs and the call-log
 * watcher and job still do the work, only slower.
 *
 * When the phone goes back to idle, the call is written to the call log a moment later: this waits
 * for it (up to about 8 seconds), then checks, within the 10 seconds Android gives a receiver.
 */
class CallEndedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.CALL_LOG || intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_IDLE) return
        val app = context.applicationContext
        val graph = app.graph
        if (!graph.auth.isSignedIn || !graph.store.syncEnabledHint || !graph.book.hasCallLogPermission()) return
        val endedAt = System.currentTimeMillis()
        val pending = goAsync()
        graph.scope.launch {
            try {
                // Wait until the call that just ended is in the call log.
                var waited = 0L
                while (waited < WAIT_MS) {
                    delay(STEP_MS); waited += STEP_MS
                    // The new call shows up as one not yet handled.
                    if (runCatching { AfterCall.hasUnhandled(app) }.getOrDefault(false)) break
                }
                AfterCall.checkUntilDone(app)
                Log.i(TAG, "call ended: checked ${System.currentTimeMillis() - endedAt} ms after")
            } catch (e: Exception) {
                Log.w(TAG, "check after call ended failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val STEP_MS = 400L
        const val WAIT_MS = 8_000L
        const val TAG = "FoundWithAfterCall"
    }
}
