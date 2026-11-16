package app.foundwith.data

import app.foundwith.BuildConfig
import app.foundwith.core.ChangeSet
import app.foundwith.core.ContactEntry
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** An error the API answered with. [message] is written for people and can be shown as is. */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val retryAfterSeconds: Long? = null,
    val details: JsonObject? = null,
) : Exception(message)

/** The request never got an answer: no network, a timeout, or the server unreachable. */
class OfflineException(cause: Throwable?) :
    Exception("Found With can't be reached right now. Check your connection and try again.", cause)

class ApiClient(private val baseUrl: String, private val auth: AuthManager) {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** After a call: short, so a slow network never keeps the phone awake for long. */
    private val callTime = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- profile and sync

    suspend fun profile(): Profile = decode(send("GET", "/v1/me"))

    /** Signing in creates the account straight away; the name comes later, with [rename]. */
    suspend fun createProfile(displayName: String? = null): Profile =
        decode(send("POST", "/v1/me", buildJsonObject { if (!displayName.isNullOrBlank()) put("display_name", displayName) }))

    suspend fun rename(displayName: String): Profile =
        decode(send("PATCH", "/v1/me", buildJsonObject { put("display_name", displayName) }))

    suspend fun deleteAccount(): DeleteResult = decode(send("DELETE", "/v1/me"))

    suspend fun setEveningNote(on: Boolean): Profile =
        decode(send("PUT", "/v1/me/settings", buildJsonObject { put("evening_note", on) }))

    suspend fun myData(): MyData = decode(send("GET", "/v1/me/data"))

    /** Everything stored about the person, as the JSON file they can save. */
    suspend fun export(): String = send("GET", "/v1/me/export")

    /** The phone's push token, so the evening note and wake-ups reach it. */
    suspend fun saveDevice(token: String) { send("PUT", "/v1/me/device", buildJsonObject { put("token", token) }) }

    suspend fun forgetDevice(token: String) { send("DELETE", "/v1/me/device", buildJsonObject { put("token", token) }) }

    /** One day of after-call health, by brand only. */
    suspend fun sendHealth(day: String, brand: String, android: Int, callsSeen: Int, callsChecked: Int) {
        send("POST", "/v1/me/health", buildJsonObject {
            put("day", day)
            put("brand", brand)
            put("android", android)
            put("calls_seen", callsSeen.coerceIn(0, 2000))
            put("calls_checked", callsChecked.coerceIn(0, 2000))
        })
    }

    suspend fun setSync(enabled: Boolean, consentVersion: String?): Profile =
        decode(send("PUT", "/v1/me/sync", buildJsonObject {
            put("enabled", enabled)
            if (consentVersion != null) put("consent_version", consentVersion)
        }))

    /** [contacts] are snapshot entries (see [ContactEntry]): each goes with the name it is saved under. */
    suspend fun syncNumbers(contacts: Collection<String>, callLog: Collection<String>): SyncResult =
        decode(send("PUT", "/v1/me/numbers", buildJsonObject {
            put("contacts", contactEntries(contacts))
            put("call_log", strings(callLog))
        }))

    suspend fun sendChanges(changes: ChangeSet): SyncResult =
        decode(send("POST", "/v1/me/numbers/changes", buildJsonObject {
            put("contacts_added", contactEntries(changes.contactsAdded))
            put("contacts_removed", strings(changes.contactsRemoved.map { ContactEntry.raw(it) }))
            put("call_log_added", strings(changes.callLogAdded))
            put("call_log_removed", strings(changes.callLogRemoved))
        }))

    // ---------------------------------------------------------------- cards and lookups

    suspend fun cards(cursor: String?, limit: Int): CardsPage {
        val query = buildString {
            append("?limit=").append(limit)
            if (cursor != null) append("&cursor=").append(cursor)
        }
        return decode(send("GET", "/v1/cards$query"))
    }

    /** context is "search" or "call". Numbers go in the body, never the URL, so they stay out of logs. */
    suspend fun lookup(number: String, context: String): LookupResult =
        decode(send("POST", "/v1/lookup", buildJsonObject {
            put("number", number)
            put("context", context)
        }, client = if (context == "call") callTime else http))

    suspend fun holders(ref: String, cursor: String?, limit: Int = 50): HoldersPage {
        val query = buildString {
            append("?limit=").append(limit)
            if (cursor != null) append("&cursor=").append(cursor)
        }
        return decode(send("GET", "/v1/numbers/$ref/holders$query"))
    }

    // ---------------------------------------------------------------- circle and invites

    /** How many people the viewer knows are on Found With, and their invite link. */
    suspend fun circle(): Circle = decode(send("GET", "/v1/me/circle"))

    suspend fun redeemInvite(code: String): InviteRedeemed =
        decode(send("POST", "/v1/me/invite/redeem", buildJsonObject { put("code", code) }))

    // ---------------------------------------------------------------- blocks (people, never calls)

    suspend fun blocks(): BlocksList = decode(send("GET", "/v1/blocks"))
    suspend fun block(userId: String) { send("POST", "/v1/blocks", buildJsonObject { put("user_id", userId) }) }
    suspend fun unblock(blockId: Long) { send("DELETE", "/v1/blocks/$blockId") }

    // ---------------------------------------------------------------- plumbing

    private fun strings(values: Collection<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun contactEntries(entries: Collection<String>): JsonArray = JsonArray(entries.map { entry ->
        val name = ContactEntry.name(entry)
        if (name == null) JsonPrimitive(ContactEntry.raw(entry))
        else buildJsonObject { put("number", ContactEntry.raw(entry)); put("name", name) }
    })

    /** A reply this version can't read (the server changed shape) is an error to show, never a crash. */
    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString(text)
    } catch (e: IllegalArgumentException) {
        throw ApiException(-1, "unreadable_reply", "Found With answered in a way this version can't read. Update the app and try again.")
    }

    /** Sends one request and returns the body. A 401 is retried once with a fresh sign-in token. */
    private suspend fun send(
        method: String,
        path: String,
        body: JsonElement? = null,
        client: OkHttpClient = http,
        retryAuth: Boolean = true,
    ): String {
        val token = try {
            auth.idToken(forceRefresh = !retryAuth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OfflineException(e)
        } ?: throw ApiException(401, "unauthenticated", "Sign in to continue.")

        val requestBody: RequestBody? = when {
            body != null -> body.toString().toRequestBody(JSON_MEDIA)
            method == "GET" -> null
            method == "DELETE" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("X-App-Version", BuildConfig.VERSION_CODE.toString())
            .method(method, requestBody)
            .build()

        val reply = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw OfflineException(e)
        }
        if (reply.code in 200..299) return reply.text
        val error = runCatching { json.decodeFromString<ApiErrorBody>(reply.text).error }.getOrNull()
        if (reply.code == 401 && retryAuth && error?.code == "unauthenticated") {
            return send(method, path, body, client, retryAuth = false)
        }
        throw ApiException(
            status = reply.code,
            code = error?.code ?: "http_${reply.code}",
            message = error?.message ?: "Something went wrong (${reply.code}). Try again in a moment.",
            retryAfterSeconds = reply.retryAfter,
            details = error?.details,
        )
    }

    private class Reply(val code: Int, val retryAfter: Long?, val text: String)

    /**
     * Runs the call and reads the whole reply on OkHttp's own thread, never the caller's (often
     * the main thread). The response is closed here, so a cancelled caller can't leak it.
     */
    private suspend fun Call.await(): Reply = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { Reply(it.code, it.header("Retry-After")?.toLongOrNull(), it.body?.string().orEmpty()) }
                } catch (e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                    return
                }
                if (!cont.isCancelled) cont.resume(reply)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
