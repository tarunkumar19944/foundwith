package app.foundwith.call

import android.content.Context
import android.os.Build
import android.provider.CallLog
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.foundwith.BuildConfig
import app.foundwith.core.AfterCallRules
import app.foundwith.core.Alert
import app.foundwith.core.Brands
import app.foundwith.core.CallCatchUp
import app.foundwith.core.CallEvent
import app.foundwith.core.CallerInfo
import app.foundwith.core.HealthDay
import app.foundwith.core.Naming
import app.foundwith.data.ApiException
import app.foundwith.data.OfflineException
import app.foundwith.graph
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * After a call: who knows the caller. WhoHas is never the phone's caller ID app, so it learns
 * about a call from the call log, three ways (whichever comes first handles the call, once):
 *  1. Android wakes it when the call log changes (a content trigger, no extra permission).
 *  2. Every time the app opens.
 *  3. A wake-up from the server a few times a day, for phones that stop apps in the background.
 * A call is only notified about within the hour; later than that it just shows in Calls, and it
 * counts as "late" for the phone-settings fix and the brand health numbers.
 */
object AfterCall {
    private const val WATCH = "found-with-after-call-watch"
    private const val NOW = "found-with-after-call-now"
    private const val RETRY = "found-with-after-call-retry"
    private val lock = Mutex()

    /** Arms the call-log trigger. Each run re-arms it, so the chain never breaks. */
    fun watch(context: Context, rearm: Boolean = false) {
        if (!BuildConfig.CALL_LOG) return
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CallLog.Calls.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(2))
            .setTriggerContentMaxDelay(Duration.ofSeconds(15))
            .build()
        val request = OneTimeWorkRequestBuilder<AfterCallWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH,
            if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Check now (app opened, or a wake-up from the server). */
    fun runNow(context: Context) {
        if (!BuildConfig.CALL_LOG) return
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AfterCallWorker>().build())
    }

    /**
     * No internet just after a call, or a busy server: try again shortly, when online (still only
     * within the hour). One retry job at a time; it keeps retrying itself until the check succeeds.
     */
    private fun retryWhenOnline(context: Context) {
        val request = OneTimeWorkRequestBuilder<AfterCallRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Duration.ofSeconds(20))
            .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofSeconds(30))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WATCH)
        wm.cancelUniqueWork(NOW)
        wm.cancelUniqueWork(RETRY)
    }

    /** Handles every call not yet handled. Returns false when some call needs a network retry. */
    suspend fun check(context: Context): Boolean = lock.withLock {
        val graph = context.graph
        // Only after the person agreed on the Allow card (Play's disclosure rule), even if the call
        // log permission was granted some other way.
        if (!graph.auth.isSignedIn || !graph.store.syncEnabledHint || !graph.book.hasCallLogPermission()) return@withLock true
        val now = System.currentTimeMillis()
        val (handledIds, lateAt, healthNow) = graph.store.callState()
        val handled = handledIds.toMutableSet()
        val late = lateAt.toMutableList()
        val zone = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.now(zone).toString()
        var health = healthNow ?: HealthDay(today)
        if (health.day != today) {
            sendHealth(context, health)
            health = HealthDay(today)
        }

        val calls = graph.book.calls(sinceMs = now - CallCatchUp.LOOK_BACK_MS, limit = 300)
        if (STARTED !in handled) {
            // First run after allowing the call log: calls from before that are history, not news.
            // Calls after it are news even when this first run comes late, for example when the
            // first call after install is what wakes it. When the moment of allowing isn't known
            // (allowed in Android settings, or an older version), the install time stands in.
            val since = graph.store.callsWatchedSince.takeIf { it > 0 }
                ?: runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrNull()
                ?: now
            val history = calls.filter { it.at < since }.map { it.id }
            graph.store.saveCallState(history + STARTED, late, health)
            handled += history
            handled += STARTED
            if (history.size == calls.size) return@withLock true
        }
        val plan = CallCatchUp.plan(calls, handled, now)
        Log.i(TAG, "check: ${calls.size} calls, ${plan.toNotify.size} to check, ${plan.tooLate.size} late")
        val contacts = graph.book.contacts().mapNotNull { c -> c.e164?.let { it to c } }.toMap()
        val index = graph.store.index()
        val baseline = graph.store.changeLog()?.baseline.orEmpty()
        val blocked = graph.store.blockedRefs
        val hour = LocalTime.now(zone).hour
        var needsRetry = false

        // Both call switches off: nothing will be shown, so nothing is looked up (no network, no battery).
        val notifying = graph.store.notifyUnknownCalls || graph.store.notifyOtherCalls
        for (call in plan.toNotify) {
            val e164 = call.e164
            if (e164 == null || !notifying) {          // hidden number, or calls switched off: nothing to look up
                handled += call.id
                continue
            }
            val info = try {
                callerInfo(context, e164, contacts[e164]?.shownName, index.refByE164[e164], baseline, blocked)
            } catch (e: OfflineException) {
                needsRetry = true
                continue
            } catch (e: ApiException) {
                Log.w(TAG, "after-call lookup failed: ${e.code}")
                // A busy server (too many requests, or a moment's outage) isn't a reason to drop
                // the call: try again shortly, still only within the hour.
                if (e.code == "rate_limited" || e.status >= 500) {
                    needsRetry = true
                    continue
                }
                null
            }
            handled += call.id
            health = health.seen(checkedInTime = true)
            if (info == null) continue
            val note = AfterCallRules.decide(call, info, hour, CallCatchUp.repeats(call, calls))
            Log.i(TAG, "call ${call.id} ${call.type}: ${note.alert} (has ${info.total}, name ${info.ownerName != null})")
            val wanted = when (note.alert) {
                Alert.SOUND -> graph.store.notifyUnknownCalls
                Alert.SILENT -> graph.store.notifyOtherCalls
                Alert.NONE -> false
            }
            if (wanted) {
                graph.notifier.postCall(note)
                graph.store.setLastNotified(e164, info.total)
            }
        }
        // Older calls from the same number were folded into the newest one's notification.
        for (call in calls) if (call.id !in handled && now - call.at <= CallCatchUp.NOTIFY_WITHIN_MS &&
            plan.toNotify.any { it.e164 == call.e164 && it.id in handled }) handled += call.id
        for (call in plan.tooLate) {
            handled += call.id
            health = health.seen(checkedInTime = false)
            late += now
        }
        graph.store.saveCallState(handled, late.filter { now - it <= CallCatchUp.LOOK_BACK_MS }, health)
        if (needsRetry) retryWhenOnline(context)
        !needsRetry
    }

    private suspend fun callerInfo(context: Context, e164: String, contactName: String?, ref: String?,
                                   baseline: Map<String, Int>, blocked: Set<String>): CallerInfo {
        val graph = context.graph
        val result = graph.api.lookup(e164, "call")
        // The card opened from the notification shows this same result, without looking again.
        graph.lookups[e164] = result
        runCatching { graph.store.saveCallLookup(e164, result) }
        val names = (result.known).map { holder ->
            val label = holder.contactRef?.let { graph.store.index().byRef[it] }
            Naming.person(label?.name, holder.name, label?.e164)
        }.filterNot { Naming.looksLikeNumber(it) }   // "Known to +91 …" tells nobody anything
        return CallerInfo(
            contactName = contactName,
            total = result.total,
            lowerBound = result.totalIsLowerBound,
            knownNames = names,
            ownerName = result.name,
            popularName = result.popularName,
            previousTotal = (ref ?: result.ref).let { baseline[it] },
            blocked = result.ref in blocked || result.hidden || result.takenOut,
            lastNotified = context.graph.store.lastNotified(e164),
        )
    }

    private suspend fun sendHealth(context: Context, day: HealthDay) {
        if (day.callsSeen == 0) return
        runCatching {
            context.graph.api.sendHealth(day.day, Brands.reportName(Build.MANUFACTURER), Build.VERSION.SDK_INT,
                day.callsSeen, day.callsChecked)
        }
    }

    private const val TAG = "FoundWithAfterCall"
    /** Marks that the first run has happened (call log ids are never negative). */
    private const val STARTED = -1L
}

class AfterCallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            AfterCall.check(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FoundWithAfterCall", "check failed", e)
        }
        AfterCall.watch(applicationContext, rearm = true)
        return Result.success()
    }
}

/** Retries the check until it gets through (see AfterCall.retryWhenOnline). */
class AfterCallRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        when {
            AfterCall.check(applicationContext) -> Result.success()
            runAttemptCount < 40 -> Result.retry()   // linear 30 s steps: well past the hour
            else -> Result.success()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("FoundWithAfterCall", "retry failed", e)
        if (runAttemptCount < 40) Result.retry() else Result.success()
    }
}
