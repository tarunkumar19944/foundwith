package app.foundwith.data

import android.app.Activity
import android.content.Context
import app.foundwith.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

/**
 * Phone sign-in. Production uses Identity Platform (Firebase Authentication) with an SMS code;
 * the API checks the ID token on every request. Debug builds can instead use "dev:<number>"
 * tokens against a local backend started with AUTH_MODE=dev.
 */
class AuthManager(private val context: Context, private val store: LocalStore) {

    /** True when the build has google-services.json, so SMS sign-in is possible. */
    val smsSignInAvailable: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    val devSignInAllowed: Boolean
        get() = BuildConfig.DEBUG && BuildConfig.DEV_SIGN_IN

    private val firebase: FirebaseAuth?
        get() = if (smsSignInAvailable) FirebaseAuth.getInstance() else null

    val isSignedIn: Boolean
        get() = devPhone() != null || firebase?.currentUser != null

    /** The verified number of whoever is signed in, in E.164 form. */
    fun signedInPhone(): String? = devPhone() ?: firebase?.currentUser?.phoneNumber

    private fun devPhone(): String? = if (devSignInAllowed) store.devPhone else null

    suspend fun idToken(forceRefresh: Boolean): String? {
        devPhone()?.let { return "dev:$it" }
        val user = firebase?.currentUser ?: return null
        return user.getIdToken(forceRefresh).await().token
    }

    /** Sends the SMS code. Results arrive through [callbacks] on the main thread. */
    fun sendCode(
        activity: Activity,
        e164: String,
        resendToken: PhoneAuthProvider.ForceResendingToken?,
        callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks,
    ) {
        val auth = firebase ?: error("SMS sign-in isn't set up in this build")
        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(e164)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
        if (resendToken != null) builder.setForceResendingToken(resendToken)
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    suspend fun signIn(credential: PhoneAuthCredential) {
        val auth = firebase ?: error("SMS sign-in isn't set up in this build")
        auth.signInWithCredential(credential).await()
    }

    suspend fun signInWithCode(verificationId: String, code: String) {
        signIn(PhoneAuthProvider.getCredential(verificationId, code))
    }

    fun devSignIn(e164: String) {
        check(devSignInAllowed) { "dev sign-in is only for debug builds" }
        store.devPhone = e164
    }

    fun signOut() {
        store.devPhone = null
        firebase?.signOut()
    }
}
