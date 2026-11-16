package app.foundwith.data

import android.content.Context
import app.foundwith.core.InviteList
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

/**
 * Invite links. A friend's link opens a page with a Google Play button carrying the invite code;
 * after install, Google Play hands the code to the app (the Install Referrer), and once the new
 * person has signed in the app redeems it, so the inviter hears that they joined. No reward.
 */
object Invites {
    /** Play's link for people without an invite link, such as in a shared card. */
    const val PLAY_STORE = "https://play.google.com/store/apps/details?id=app.foundwith"

    /**
     * Asks Google Play, once per install, which link the app was installed from, and keeps the
     * invite code if there was one. Quick and silent; does nothing for installs from outside Play.
     */
    fun captureReferrer(context: Context, store: LocalStore) {
        if (store.referrerChecked) return
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        runCatching {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val referrer = runCatching { client.installReferrer.installReferrer }.getOrNull()
                            InviteList.codeFrom(referrer)?.let { store.pendingInvite = it }
                            store.referrerChecked = true
                        }
                        // Not installed from Play, or this Play version can't tell: nothing to wait for.
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                        InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR -> store.referrerChecked = true
                        // Play is busy or updating: try again on the next start.
                        else -> Unit
                    }
                    runCatching { client.endConnection() }
                }

                override fun onInstallReferrerServiceDisconnected() = Unit
            })
        }
    }

    /**
     * Redeems the invite the app was installed with, if any. Returns null when there was nothing
     * to redeem. A code the server refuses (wrong, own, too late, already used)
     * is forgotten; one that couldn't be sent because of the network is kept for next time.
     */
    suspend fun redeemPending(api: ApiClient, store: LocalStore): InviteRedeemed? {
        val code = store.pendingInvite ?: return null
        return try {
            api.redeemInvite(code).also { store.pendingInvite = null }
        } catch (e: ApiException) {
            if (e.code == "invite_not_found" || e.code == "invite_rejected" || e.code == "invalid_request") {
                store.pendingInvite = null
            }
            null
        } catch (e: OfflineException) {
            null
        }
    }
}
