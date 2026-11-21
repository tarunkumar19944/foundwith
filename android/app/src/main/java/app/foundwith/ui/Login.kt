package app.foundwith.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foundwith.AppGraph
import app.foundwith.BuildConfig
import app.foundwith.core.PhoneNumbers
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where sign-in stands. Each has its own screen. */
enum class LoginStep { WELCOME, CHECKING, TYPE_NUMBER, NOT_HERE, CODE, TOO_MANY, NO_INTERNET }

/**
 * Sign-in works only with a SIM that's in this phone (agreed 17 Nov 2026). Android's own sheet
 * offers the SIMs in the phone; the SMS code is then accepted only when this phone reads it
 * itself, silently through Firebase or through Android's "Allow WhoHas to read this message?".
 * So the SMS has to land here. Typing a code by hand is allowed only in test builds and for the
 * one number Google Play's reviewers use ([BuildConfig.REVIEW_NUMBER]).
 */
class LoginViewModel(private val graph: AppGraph) : ViewModel() {
    var step by mutableStateOf(LoginStep.WELCOME)
        private set
    var number by mutableStateOf("")
    var code by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    var e164 by mutableStateOf<String?>(null)
        private set
    var resendIn by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Waiting for the SMS on this phone through Android's consent prompt (up to 5 minutes). */
    var listening by mutableStateOf(false)
        private set
    /** Goes up with every code sent, so the prompt starts listening afresh for each SMS. */
    var listenRound by mutableIntStateOf(0)
        private set
    /** The sheet opens by itself once, on the first screen. */
    var sheetShown = false

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var onSignedIn: (() -> Unit)? = null

    fun bind(done: () -> Unit) {
        onSignedIn = done
    }

    /** A code may be typed by hand only in test builds and for Play's review number. */
    private fun canType(number: String?): Boolean =
        BuildConfig.DEBUG || (BuildConfig.REVIEW_NUMBER.isNotBlank() && number == BuildConfig.REVIEW_NUMBER)

    /** The sheet was closed, offered no number, or the person wants a different one. */
    fun typeNumber() {
        error = null
        listening = false
        step = LoginStep.TYPE_NUMBER
    }

    fun picked(activity: Activity?, raw: String) {
        number = PhoneNumbers.normalise(raw)?.let { PhoneNumbers.display(it).removePrefix("+91 ") } ?: raw
        sendCode(activity)
    }

    fun sendCode(activity: Activity?) {
        val parsed = PhoneNumbers.normalise(number)
        if (parsed == null || !PhoneNumbers.isMobileShaped(parsed)) {
            error = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9."
            step = LoginStep.TYPE_NUMBER
            return
        }
        e164 = parsed
        error = null
        if (graph.auth.devSignInAllowed) {
            graph.auth.devSignIn(parsed)
            finish()
            return
        }
        if (!graph.auth.smsSignInAvailable || activity == null) {
            error = "This build can't send sign-in codes: google-services.json is missing. See android/README.md."
            step = LoginStep.TYPE_NUMBER
            return
        }
        step = LoginStep.CHECKING
        busy = true
        // Listen before sending: the SMS can arrive within a second or two. Firebase's own silent
        // read needs an app code in the SMS that India's SMS often lacks; Android's "Allow WhoHas
        // to read this message?" prompt works with any SMS that lands on this phone.
        listenRound += 1
        listening = true
        graph.auth.sendCode(activity, parsed, resendToken, callbacks)
    }

    private val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            // This phone read the code from the SMS itself (or Google checked the number on this phone).
            listening = false
            signIn { graph.auth.signIn(credential) }
        }

        override fun onVerificationFailed(e: FirebaseException) {
            busy = false
            listening = false
            when {
                e is FirebaseTooManyRequestsException -> step = LoginStep.TOO_MANY
                e is FirebaseNetworkException -> step = LoginStep.NO_INTERNET
                // Only a badly formed number means "check the number"; the same exception type also
                // carries app-verification failures, which are ours to fix, not the person's.
                e is FirebaseAuthInvalidCredentialsException && e.errorCode == "ERROR_INVALID_PHONE_NUMBER" -> {
                    error = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9."
                    step = LoginStep.TYPE_NUMBER
                }
                else -> {
                    error = withDetail("Couldn't send the code. Try again in a minute.", e)
                    step = LoginStep.TYPE_NUMBER
                }
            }
        }

        override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
            busy = false
            verificationId = id
            resendToken = token
            startResendTimer()
            if (canType(e164)) {
                // Test builds and the review number may also type the code (test numbers get no SMS).
                // Android's prompt keeps listening meanwhile, so a real SMS still fills it in.
                viewModelScope.launch {
                    delay(if (BuildConfig.DEBUG) 6000 else 0)
                    if (step == LoginStep.CHECKING) step = LoginStep.CODE
                }
            }
        }

        override fun onCodeAutoRetrievalTimeOut(id: String) {
            if (step != LoginStep.CHECKING) return
            step = if (canType(e164)) LoginStep.CODE else LoginStep.NOT_HERE
        }
    }

    private fun startResendTimer() {
        viewModelScope.launch {
            resendIn = 30
            while (resendIn > 0) {
                delay(1000)
                resendIn -= 1
            }
        }
    }

    /** The code, read from an SMS that arrived on this phone (Android's consent prompt). */
    fun codeFromThisPhone(digits: String) {
        val id = verificationId ?: return
        listening = false
        code = digits
        signIn { graph.auth.signInWithCode(id, digits) }
    }

    /** Typed by hand: test builds and the review number only. */
    fun verifyCode() {
        val id = verificationId ?: return
        if (!canType(e164)) return
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) {
            error = "Enter the 6-digit code."
            return
        }
        signIn { graph.auth.signInWithCode(id, digits) }
    }

    fun changeNumber() {
        code = ""
        verificationId = null
        error = null
        listening = false
        step = LoginStep.TYPE_NUMBER
    }

    fun tryAgain(activity: Activity?) {
        error = null
        if (e164 != null) sendCode(activity) else typeNumber()
    }

    private fun signIn(block: suspend () -> Unit) {
        busy = true
        viewModelScope.launch {
            try {
                block()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                error = "Wrong code. Check the SMS and try again."
                step = if (canType(e164)) LoginStep.CODE else LoginStep.NOT_HERE
            } catch (e: FirebaseTooManyRequestsException) {
                step = LoginStep.TOO_MANY
            } catch (e: FirebaseNetworkException) {
                step = LoginStep.NO_INTERNET
            } catch (e: Exception) {
                error = withDetail("Something went wrong. Try again.", e)
                step = if (canType(e164)) LoginStep.CODE else LoginStep.NOT_HERE
            } finally {
                busy = false
            }
        }
    }

    private fun finish() {
        listening = false
        viewModelScope.launch {
            // The account is created straight away, with no name; Home asks for the name later.
            runCatching { graph.model.refresh() }
            onSignedIn?.invoke()
        }
    }
}

@Composable
fun LoginScreen(graph: AppGraph, onDone: () -> Unit) {
    val vm = appViewModel { LoginViewModel(graph) }
    vm.bind(onDone)
    val context = LocalContext.current
    val activity = context.findActivity()
    val deleted = rememberSaveable { graph.justDeleted.also { graph.justDeleted = false } }
    var deletedShown by rememberSaveable { mutableStateOf(deleted) }
    val invited = graph.store.pendingInvite != null

    val hint = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val phone = if (result.resultCode == Activity.RESULT_OK && activity != null) {
            runCatching { Identity.getSignInClient(activity).getPhoneNumberFromIntent(result.data) }.getOrNull()
        } else null
        if (phone.isNullOrBlank()) vm.typeNumber() else vm.picked(activity, phone)
    }
    fun openSheet() {
        if (activity == null) return vm.typeNumber()
        Identity.getSignInClient(activity)
            .getPhoneNumberHintIntent(GetPhoneNumberHintIntentRequest.builder().build())
            .addOnSuccessListener { pending -> runCatching { hint.launch(IntentSenderRequest.Builder(pending).build()) }.onFailure { vm.typeNumber() } }
            .addOnFailureListener { vm.typeNumber() }   // no SIM number, no Google Play services: type it
    }
    // The picker opens by itself over the Welcome screen: no tap needed to start.
    LaunchedEffect(Unit) {
        if (vm.step == LoginStep.WELCOME && !vm.sheetShown && !deleted) {
            vm.sheetShown = true
            openSheet()
        }
    }

    // Android's own "Allow WhoHas to read this message?" for the sign-in SMS. The SMS must land on
    // this phone, so only a SIM in this phone can sign in.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
            Regex("""(?<!\d)(\d{6})(?!\d)""").find(message)?.groupValues?.get(1)?.let { vm.codeFromThisPhone(it) }
        }
    }
    DisposableEffect(vm.listening, vm.listenRound, activity) {
        val host = activity
        if (!vm.listening || host == null) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                @Suppress("DEPRECATION")
                val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode != CommonStatusCodes.SUCCESS) return
                val ask: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(SmsRetriever.EXTRA_CONSENT_INTENT)
                }
                // Only ever open Google Play services' own prompt, and never pass on URI grants.
                val target = ask?.resolveActivity(host.packageManager)
                val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (ask != null && target?.packageName == "com.google.android.gms" && (ask.flags and grants) == 0) {
                    runCatching { consent.launch(ask) }
                }
            }
        }
        runCatching { SmsRetriever.getClient(host).startSmsUserConsent(null) }
        ContextCompat.registerReceiver(host, receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { host.unregisterReceiver(receiver) } }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (vm.step) {
            LoginStep.WELCOME -> if (deletedShown) {
                // "Your account is deleted", once, after deleting in Settings (docs/WHOHAS_COPY.md).
                Spacer(Modifier.height(120.dp))
                Box(Modifier.align(Alignment.CenterHorizontally).size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                }
                Text("Your account is deleted", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(app.foundwith.core.AccountText.deleted(BuildConfig.CALL_LOG), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(48.dp))
                PrimaryButton("Done", { deletedShown = false })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Wordmark(30.sp) }
                    if (invited) Chip("A friend invited you")
                }
                Example()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Unknown number calling?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(if (BuildConfig.CALL_LOG) "See which of your people know it." else "Search it. See which of your people know it.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
                }
                if (BuildConfig.CALL_LOG) {
                    Benefit(Icons.Filled.Call, "Know your callers", "After a call, see which people you know have that number.")
                    Benefit(Icons.Filled.Search, "Check any number", "See how many people have it, and ask someone you know.")
                } else {
                    // The contacts-only version can't read calls: it promises search and contacts, nothing after a call.
                    Benefit(Icons.Filled.Search, "Check any number", "Search a number that called you. See which people you know have it.")
                    Benefit(Icons.Filled.Call, "See who has your contacts", "Each contact shows how many people on WhoHas have their number.")
                }
                Benefit(Icons.Filled.Person, "See who has your number", "Find out how many people have your number, and when it goes up.", saffron = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("WhoHas never messages or calls anyone.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PrimaryButton("Get started", { openSheet() })
                Text("Use the number of a SIM in this phone.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            LoginStep.CHECKING -> {
                Text("Checking your SIM", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("We sent an SMS to ${vm.e164?.let { shown(it) }.orEmpty()}. WhoHas reads the code on this phone by itself.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(72.dp), strokeWidth = 4.dp)
                        Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                }
                WhCard(Modifier.padding(horizontal = 0.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("If Android asks, tap Allow so WhoHas can read that one SMS.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ResendRow(vm, onResend = { vm.sendCode(activity) }, onChange = { openSheet() })
            }
            LoginStep.NOT_HERE -> {
                Text("The code didn't reach this phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("WhoHas works only with a SIM that's in this phone. Check that the SIM for " +
                    "${vm.e164?.let { shown(it) }.orEmpty()} is in this phone, then send the code again.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErrorText(vm.error)
                PrimaryButton("Send again", { vm.sendCode(activity) }, enabled = !vm.busy && vm.resendIn == 0)
                if (vm.resendIn > 0) Text("Send again in 0:${vm.resendIn.toString().padStart(2, '0')}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                OutlinedButton(onClick = { openSheet() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
                    Text("Use another number")
                }
            }
            LoginStep.TYPE_NUMBER -> {
                Text("Your mobile number", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Use the number of a SIM in this phone. We'll send it an SMS to check.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = "+91", onValueChange = {}, enabled = false, modifier = Modifier.width(72.dp))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = vm.number,
                        onValueChange = { vm.number = it.take(14) },
                        placeholder = { Text("98450 12345") },
                        isError = vm.error != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                }
                ErrorText(vm.error)
                PrimaryButton("Send code", { vm.sendCode(activity) }, enabled = !vm.busy)
            }
            LoginStep.CODE -> {
                Text("Enter the 6-digit code", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sent to ${vm.e164?.let { shown(it) }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.changeNumber() }) { Text("Change number") }
                }
                OutlinedTextField(
                    value = vm.code,
                    onValueChange = { v -> vm.code = v.filter { it.isDigit() }.take(6); if (vm.code.length == 6) vm.verifyCode() },
                    placeholder = { Text("6-digit code") },
                    isError = vm.error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(vm.error)
                if (vm.resendIn > 0) {
                    Text("Send again in 0:${vm.resendIn.toString().padStart(2, '0')}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    PrimaryButton("Send code again", { vm.sendCode(activity) }, enabled = !vm.busy)
                }
            }
            LoginStep.TOO_MANY -> {
                EmptyState(Icons.Filled.Warning, "Too many tries", "Wait 15 minutes and try again.")
                OutlinedButton(onClick = { vm.typeNumber() }, modifier = Modifier.fillMaxWidth()) { Text("OK") }
            }
            LoginStep.NO_INTERNET -> {
                EmptyState(Icons.Filled.Warning, "No internet", "Connect and try again.", button = "Try again") { vm.tryAgain(activity) }
            }
        }
    }
}

@Composable
private fun ResendRow(vm: LoginViewModel, onResend: () -> Unit, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (vm.resendIn > 0) {
            Text("Send again in 0:${vm.resendIn.toString().padStart(2, '0')}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
        } else {
            TextButton(onClick = onResend, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Send code again") }
        }
        TextButton(onClick = onChange) { Text("Change number", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Benefit(icon: ImageVector, title: String, text: String, saffron: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            color = if (saffron) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (saffron) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) { Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the app does, shown before anything is asked: a missed call, and who knows the number. */
@Composable
private fun Example() {
    WhCard(Modifier.padding(horizontal = 0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (BuildConfig.CALL_LOG) Icon(CallIcons.Missed, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            else Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("+91 98450 77120", fontWeight = FontWeight.Bold, maxLines = 1)
                if (BuildConfig.CALL_LOG) Text("Missed call · 10:42 am", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                else Text("You searched this number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PeopleStack(listOf("p" to "Priya S", "m" to "Meena", "a" to "Anil Kumar"), ring = MaterialTheme.colorScheme.surface)
            Spacer(Modifier.width(10.dp))
            Text("Known to Priya, Meena and 10 more", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Test builds add Firebase's own error under the message, so a failed sign-in can be diagnosed
 * from a screenshot. Release builds show only the plain message. Both write it to the log.
 */
private fun withDetail(message: String, e: Exception): String {
    Log.w("WhoHas", "Sign-in failed", e)
    if (!BuildConfig.DEBUG) return message
    val code = (e as? FirebaseAuthException)?.errorCode
    val detail = listOfNotNull(code, e.message?.takeIf { it.isNotBlank() }).joinToString(": ")
    return if (detail.isEmpty()) message else "$message\n\n${detail.take(300)}"
}
