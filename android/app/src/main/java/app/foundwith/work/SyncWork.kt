package app.foundwith.work

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.foundwith.BuildConfig
import app.foundwith.call.AfterCall
import app.foundwith.core.SyncPolicy
import app.foundwith.data.ApiException
import app.foundwith.data.OfflineException
import app.foundwith.graph
import java.time.Duration
import kotlinx.coroutines.CancellationException

/**
 * Background sync. Two kinds of run:
 *  - "changes": wakes up a few seconds after the contacts (or call log) change and sends only
 *    what changed, so a newly saved contact reaches cards in seconds.
 *  - "daily": sends what changed, and the whole address book once a week. The full sync is the
 *    source of truth and picks up anything deferred by the new-number allowance.
 * After calls are handled separately, by [app.foundwith.call.AfterCall], so they never wait for this.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: MODE_FULL
        if (mode != MODE_CHANGES) return if (sync(mode)) Result.success() else Result.retry()
        // A change run always succeeds and re-arms the watcher, so the chain never breaks.
        // If it couldn't send the changes, a full sync is queued for when the network is back.
        if (!sync(mode)) Scheduling.syncNow(applicationContext, delaySeconds = retryAfterSeconds ?: 0L)
        Scheduling.watchPhone(applicationContext, rearm = true)
        return Result.success()
    }

    /** Set when the server asked us to wait (429 or 503 with Retry-After). */
    private var retryAfterSeconds: Long? = null

    /** Returns false when the run should be tried again later. */
    private suspend fun sync(mode: String): Boolean {
        val graph = applicationContext.graph
        return try {
            val phoneAllowed = graph.book.hasContactsPermission() || graph.book.hasCallLogPermission()
            if (!graph.auth.isSignedIn || !phoneAllowed || !graph.store.syncEnabledHint) {
                return true
            }
            val full = mode == MODE_FULL ||
                (mode == MODE_DAILY && SyncPolicy.fullDue(graph.store.lastFullSyncAt, System.currentTimeMillis()))
            val outcome = if (full) graph.sync.fullSync() else graph.sync.syncChanges()
            Log.i(TAG, "sync $mode: ${outcome.kind} ${outcome.stats}")
            runCatching { graph.model.refresh() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: OfflineException) {
            false
        } catch (e: ApiException) {
            Log.w(TAG, "sync $mode failed: ${e.status} ${e.code}")
            retryAfterSeconds = e.retryAfterSeconds
            // Sync off, terms changed, account gone or suspended: the app asks the person next time
            // it opens, so there is nothing to retry.
            !(e.status == 429 || e.status >= 500)
        } catch (e: Exception) {
            Log.w(TAG, "sync $mode failed", e)
            false
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_FULL = "full"
        const val MODE_DAILY = "daily"
        const val MODE_CHANGES = "changes"
        private const val TAG = "FoundWithSync"
    }
}

object Scheduling {
    private const val DAILY = "found-with-daily-full-sync"
    private const val WATCH = "found-with-watch-phone"
    private const val NOW = "found-with-sync-now"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleAll(context: Context) {
        val daily = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(24))
            .setConstraints(online)
            .setInputData(workDataOf(SyncWorker.KEY_MODE to SyncWorker.MODE_DAILY))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(5))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, daily)
        watchPhone(context, rearm = false)
        AfterCall.watch(context)
    }

    /**
     * Runs a "changes" sync after the contacts or call log change. Android only reports one change
     * per scheduled run, so each run arms the next one. [rearm] appends to the running chain;
     * otherwise an existing watcher is kept as it is.
     */
    fun watchPhone(context: Context, rearm: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .addContentUriTrigger(ContactsContract.AUTHORITY_URI, true)
            .apply { if (BuildConfig.CALL_LOG) addContentUriTrigger(CallLog.Calls.CONTENT_URI, true) }
            // Wait for edits to settle, but never longer than a minute.
            .setTriggerContentUpdateDelay(Duration.ofSeconds(10))
            .setTriggerContentMaxDelay(Duration.ofMinutes(1))
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_MODE to SyncWorker.MODE_CHANGES))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        val policy = if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH, policy, request)
    }

    /**
     * A full sync as soon as the network allows (after [delaySeconds], when the server asked us to
     * wait), e.g. after unhiding a number or when a change run couldn't get through.
     */
    fun syncNow(context: Context, delaySeconds: Long = 0L) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setInputData(workDataOf(SyncWorker.KEY_MODE to SyncWorker.MODE_FULL))
            .setInitialDelay(Duration.ofSeconds(delaySeconds.coerceIn(0L, 3600L)))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        AfterCall.cancelAll(context)
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DAILY)
        wm.cancelUniqueWork(WATCH)
        wm.cancelUniqueWork(NOW)
    }
}
