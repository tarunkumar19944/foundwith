package app.foundwith.push

import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.foundwith.call.AfterCall
import app.foundwith.graph
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Push messages from the Found With server. None carries a name or a number:
 *  - "wake": check the call log for calls not yet checked (some phones stop apps in the background).
 *  - "cards", "joined", "invite": fetch the cards again, so Updates is fresh when the app opens.
 *  - "evening": the evening note. Android shows it by itself when the app isn't open; when it is,
 *    it arrives here and is shown the same way.
 */
class MessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        graph.store.pushToken = token
        if (graph.auth.isSignedIn) RefreshWorker.enqueue(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!graph.auth.isSignedIn) return
        when (message.data["why"]) {
            "wake" -> AfterCall.runNow(this)
            "evening" -> message.notification?.let { graph.notifier.postEvening(it.title.orEmpty(), it.body.orEmpty()) }
            else -> RefreshWorker.enqueue(this)
        }
    }
}

/** Fetches profile and cards in the background (and sends a new push token if there is one). */
class RefreshWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        if (!graph.auth.isSignedIn) return Result.success()
        graph.model.refresh()
        return Result.success()
    }

    companion object {
        fun enqueue(context: android.content.Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("found-with-refresh", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
