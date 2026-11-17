package app.foundwith.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.foundwith.BuildConfig
import app.foundwith.core.CallEvent
import app.foundwith.core.CallType
import app.foundwith.core.PhoneContact
import app.foundwith.core.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A number saved on the phone, exactly as typed, with the contact's name. [label] is the phone's
 * own word for the number ("Mobile", "Work") and [multiple] is true when the contact has more
 * than one number, so the Contacts tab can show "Ravi Kumar (Work)".
 */
data class SavedNumber(
    val raw: String,
    val name: String?,
    val label: String? = null,
    val starred: Boolean = false,
    val contactId: Long = 0,
    val multiple: Boolean = false,
) {
    val e164: String? get() = PhoneNumbers.normalise(raw)
    val shownName: String? get() = name?.let { if (multiple && label != null) "$it ($label)" else it }
}

/** Reads the phone's contacts and call log. */
class DeviceBook(private val context: Context) {

    fun hasContactsPermission(): Boolean = granted(Manifest.permission.READ_CONTACTS)

    /** Always false in the contactsOnly build, which doesn't declare the permission. */
    fun hasCallLogPermission(): Boolean = BuildConfig.CALL_LOG && granted(Manifest.permission.READ_CALL_LOG)

    fun hasNotificationPermission(): Boolean =
        (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Every number in contacts. Phones often hold the same contact two or three times (SIM, Google,
     * WhatsApp): the same number under the same name appears once.
     */
    suspend fun contacts(): List<SavedNumber> = withContext(Dispatchers.IO) {
        if (!hasContactsPermission()) return@withContext emptyList()
        val rows = ArrayList<SavedNumber>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
            )?.use { cursor ->
                val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val typeCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                val starredCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(numberCol)?.trim()
                    if (raw.isNullOrEmpty()) continue
                    val label = runCatching {
                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources, cursor.getInt(typeCol), cursor.getString(labelCol)).toString()
                    }.getOrNull()
                    rows += SavedNumber(raw, cursor.getString(nameCol)?.trim(), label,
                        cursor.getInt(starredCol) == 1, cursor.getLong(idCol))
                }
            }
        }
        val unique = rows.distinctBy { (it.e164 ?: it.raw) to it.name.orEmpty().lowercase() }
        val numbersPerContact = unique.groupingBy { it.contactId }.eachCount()
        unique.map { it.copy(multiple = (numbersPerContact[it.contactId] ?: 1) > 1) }
    }

    /** Contacts with whether each is a favourite, for ranking who to invite. */
    suspend fun contactsForInvite(): List<PhoneContact> = contacts().map { PhoneContact(it.raw, it.name, it.starred) }

    /**
     * Recent calls, newest first (Android keeps a few hundred). [sinceMs] limits how far back.
     * Hidden numbers come back with an empty number.
     */
    suspend fun calls(sinceMs: Long = 0L, limit: Int = 500): List<CallEvent> = withContext(Dispatchers.IO) {
        if (!hasCallLogPermission()) return@withContext emptyList()
        val out = ArrayList<CallEvent>()
        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME, CallLog.Calls.DURATION)
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, "${CallLog.Calls.DATE} >= ?", arrayOf(sinceMs.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (cursor.moveToNext() && out.size < limit) {
                    val type = when (cursor.getInt(typeCol)) {
                        CallLog.Calls.INCOMING_TYPE -> CallType.ANSWERED
                        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        else -> CallType.OTHER
                    }
                    out += CallEvent(cursor.getLong(idCol), cursor.getString(numberCol)?.trim(), type,
                        cursor.getLong(dateCol), cursor.getString(nameCol), cursor.getLong(durationCol))
                }
            }
        }
        out
    }

    /** Distinct numbers in the call log, as typed, for sync. */
    suspend fun callLogNumbers(): Set<String> =
        calls().mapNotNullTo(HashSet()) { it.raw?.takeIf { raw -> raw.isNotBlank() } }

    /** The name on the phone's own "Me" card, when there is one: to fill in "Add your name". */
    suspend fun ownerName(): String? = withContext(Dispatchers.IO) {
        if (!hasContactsPermission()) return@withContext null
        runCatching {
            context.contentResolver.query(android.provider.ContactsContract.Profile.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Profile.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() && it.any(Char::isLetter) } else null
            }
        }.getOrNull()
    }

    /** One entry per call in the call log, for counting who the person talks to most. */
    suspend fun callLogCalls(): List<String> = calls().mapNotNull { it.raw?.takeIf { raw -> raw.isNotBlank() } }
}
