package app.foundwith

import android.content.Context
import app.foundwith.call.Notifier
import app.foundwith.data.ApiClient
import app.foundwith.data.AppModel
import app.foundwith.data.AuthManager
import app.foundwith.data.DeviceBook
import app.foundwith.data.LocalStore
import app.foundwith.data.LookupResult
import app.foundwith.data.SyncEngine
import app.foundwith.work.Scheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's shared objects, created once in [FoundWithApp]. */
class AppGraph(val context: Context) {
    /** Work that must outlive a screen, such as a refresh started from a notification. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store = LocalStore(context)
    val auth = AuthManager(context, store)
    val api = ApiClient(BuildConfig.API_BASE_URL, auth)
    val book = DeviceBook(context)
    val sync = SyncEngine(api, book, store, auth)
    val notifier = Notifier(context)
    val model = AppModel(this)

    /** Search results by number, so a card opens without a second lookup. */
    val lookups = java.util.concurrent.ConcurrentHashMap<String, LookupResult>()

    /** True while a WhoHas screen is on show: change and level notifications wait, Updates shows them. */
    @Volatile var inFront = false

    /** Set by a notification's "Open Updates": Home opens on the Updates tab instead of Call log. */
    val openUpdates = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Set just before the app goes back to the start after deleting the account. */
    @Volatile var justDeleted = false

    /** Sign out on this phone and forget everything stored for this account. */
    suspend fun forgetEverything() {
        Scheduling.cancelAll(context)
        runCatching { store.pushTokenSent?.let { api.forgetDevice(it) } }
        auth.signOut()
        store.clear()
        lookups.clear()
        notifier.cancelAll()
        model.reset()
    }
}
