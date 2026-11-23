package app.foundwith.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foundwith.AppGraph
import app.foundwith.BuildConfig
import app.foundwith.core.PhoneNumbers
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
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
import kotlinx.coroutines.Job
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

/** Where sign-in stands. Each has its own screen. */
enum class LoginStep { WELCOME, TYPE_NUMBER, CODE, TOO_MANY, NO_INTERNET }

/**
 * Sign-in (22 Nov 2026): tapping a number in Android's sheet goes straight to "Enter the code"
 * while the code is sent. No "Allow WhoHas to read this message?" prompt: the code fills in by
 * itself when Google reads the SMS silently (Firebase's SMS Retriever), or from the keyboard's
 * "From Messages" suggestion (Android's SMS-code autofill), or the person types it. After
 * [CHECK_AFTER_MS] with no code, a blue "Check your messages for the code" box shows.
 */
class LoginViewModel(private val graph: AppGraph) : ViewModel() {
    var step by mutableStateOf(LoginStep.WELCOME)
        private set
    var number by mutableStateOf("")
    var code by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var e164 by mutableStateOf<String?>(null)
        private set
    var resendIn by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Signing in with a full code (filled in or typed). */
    var signingIn by mutableStateOf(false)
        private set
    /** No code yet [CHECK_AFTER_MS] after it was sent: show "Check your messages for the code". */
    var checkMessages by mutableStateOf(false)
        private set
    /** The sheet opens by itself once, on the first screen. */
    var sheetShown = false

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var onSignedIn: (() -> Unit)? = null
    private var checkTimer: Job? = null
    /** Goes up with every code sent, so a late answer about an earlier send is ignored. */
    private var round = 0

    fun bind(done: () -> Unit) {
        onSignedIn = done
    }

    /** The sheet was closed, offered no number, or the person wants a different one. */
    fun typeNumber() {
        error = null
        checkTimer?.cancel()
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
        // Straight to the code screen: the code is sent meanwhile.
        round += 1
        code = ""
        signingIn = false
        checkMessages = false
        checkTimer?.cancel()
        verificationId = null
        busy = true
        step = LoginStep.CODE
        graph.auth.sendCode(activity, parsed, resendToken, Callbacks(round))
    }

    private inner class Callbacks(private val sent: Int) : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        private val current get() = sent == round

        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            if (!current) return
            // Google read the code from the SMS on this phone (or checked the number on this phone):
            // show the code in the boxes, then sign in.
            credential.smsCode?.let { code = it.take(CODE_LENGTH) }
            signIn { graph.auth.signIn(credential) }
        }

        override fun onVerificationFailed(e: FirebaseException) {
            if (!current) return
            busy = false
            checkTimer?.cancel()
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
            if (!current) return
            busy = false
            verificationId = id
            resendToken = token
            startResendTimer()
            checkTimer = viewModelScope.launch {
                delay(CHECK_AFTER_MS)
                if (step == LoginStep.CODE && !signingIn) checkMessages = true
            }
            // Typed (or filled in) before the send finished: check it now.
            if (code.length == CODE_LENGTH) verifyCode()
        }

        override fun onCodeAutoRetrievalTimeOut(id: String) {
            if (current && step == LoginStep.CODE && !signingIn) checkMessages = true
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

    /** Every change to the code boxes: typed, pasted, or filled in from the SMS by the keyboard or Android. */
    fun codeChanged(value: String) {
        if (signingIn) return
        code = value.filter { it.isDigit() }.take(CODE_LENGTH)
        if (code.isNotEmpty()) error = null
        if (code.length == CODE_LENGTH) verifyCode()
    }

    private fun verifyCode() {
        val id = verificationId ?: return        // still sending: checked in onCodeSent
        val digits = code
        signIn { graph.auth.signInWithCode(id, digits) }
    }

    fun tryAgain(activity: Activity?) {
        error = null
        if (e164 != null) sendCode(activity) else typeNumber()
    }

    private fun signIn(block: suspend () -> Unit) {
        if (signingIn) return
        signingIn = true
        checkMessages = false
        viewModelScope.launch {
            try {
                block()
                checkTimer?.cancel()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                error = "Wrong code. Check the SMS and try again."
                code = ""
            } catch (e: FirebaseTooManyRequestsException) {
                step = LoginStep.TOO_MANY
            } catch (e: FirebaseNetworkException) {
                step = LoginStep.NO_INTERNET
            } catch (e: Exception) {
                error = withDetail("Something went wrong. Try again.", e)
                code = ""
            } finally {
                signingIn = false
            }
        }
    }

    private fun finish() {
        viewModelScope.launch {
            // The account is created straight away, with no name; Home asks for the name later.
            runCatching { graph.model.refresh() }
            onSignedIn?.invoke()
        }
    }

    companion object {
        const val CODE_LENGTH = 6
        const val CHECK_AFTER_MS = 10_000L
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

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (vm.step) {
            LoginStep.WELCOME -> if (deletedShown) {
                DeletedView { deletedShown = false }
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
                AgreeLine()
            }
            LoginStep.TYPE_NUMBER -> {
                Text("Your mobile number", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("We'll send an SMS with a 6-digit code.",
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
                Text("Enter the code", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sent by SMS to ${vm.e164?.let { shown(it) }.orEmpty()} · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { openSheet() }.padding(vertical = 12.dp))
                }
                CodeBoxes(vm.code, enabled = !vm.signingIn, error = vm.error != null, onChange = vm::codeChanged)
                CodeStatus(vm)
                if (vm.resendIn > 0) {
                    Text("Send again in 0:${vm.resendIn.toString().padStart(2, '0')}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Send code again", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = !vm.busy && !vm.signingIn) { vm.sendCode(activity) }.padding(vertical = 12.dp))
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

/**
 * Six boxes over one number field. The field is marked as the SMS code for Android's autofill, so
 * the keyboard or Google's autofill can offer the code from the SMS; a full code signs in at once.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CodeBoxes(code: String, enabled: Boolean, error: Boolean, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val autofill = androidx.compose.ui.platform.LocalAutofill.current
    val node = remember {
        androidx.compose.ui.autofill.AutofillNode(
            autofillTypes = listOf(androidx.compose.ui.autofill.AutofillType.SmsOtpCode),
            onFill = { onChange(it) },
        )
    }
    androidx.compose.ui.platform.LocalAutofillTree.current += node
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val blue = MaterialTheme.colorScheme.primary
    val red = MaterialTheme.colorScheme.error
    androidx.compose.foundation.text.BasicTextField(
        value = code,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
            .onFocusChanged { state ->
                autofill?.run { if (state.isFocused) requestAutofillForNode(node) else cancelAutofillForNode(node) }
            }
            .semantics { contentDescription = "6-digit code" },
        decorationBox = { inner ->
            Box {
                // The real field sits underneath, invisible: the boxes show what's in it.
                Box(Modifier.size(1.dp).alpha(0f)) { inner() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (i in 0 until LoginViewModel.CODE_LENGTH) {
                        val digit = code.getOrNull(i)?.toString().orEmpty()
                        val current = i == code.length && enabled
                        val edge = when {
                            error -> red
                            digit.isNotEmpty() || current -> blue
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        Box(
                            Modifier.weight(1f).height(58.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                .border(2.dp, edge, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (digit.isNotEmpty()) Text(digit, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            else if (current) Box(Modifier.width(2.dp).height(26.dp).background(blue))
                        }
                    }
                }
            }
        },
    )
}

/** The line under the boxes: waiting, signing in, a wrong code, or the blue "Check your messages" box. */
@Composable
private fun CodeStatus(vm: LoginViewModel) = CodeStatus(vm.signingIn, vm.error, vm.checkMessages)

@Composable
internal fun CodeStatus(signingIn: Boolean, error: String?, checkMessages: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        signingIn -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Signing you in…", color = muted)
        }
        error != null -> Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        checkMessages -> Surface(
            color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Check your messages for the code", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Waiting for the SMS. The code fills in by itself.", color = muted)
        }
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

/** "Your account is deleted", once, after deleting in Settings (docs/WHOHAS_COPY.md). */
@Composable
fun DeletedView(onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
        PrimaryButton("Done", onDone)
    }
}

/** Under Get started: what signing up agrees to, with both pages one tap away. */
@Composable
internal fun AgreeLine() {
    if (TERMS_URL.isBlank() || PRIVACY_URL.isBlank()) return
    val link = androidx.compose.ui.text.TextLinkStyles(
        androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold))
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append("By continuing, you agree to the ")
        withLink(androidx.compose.ui.text.LinkAnnotation.Url(TERMS_URL, link)) { append("Terms of use") }
        append(" and ")
        withLink(androidx.compose.ui.text.LinkAnnotation.Url(PRIVACY_URL, link)) { append("Privacy policy") }
        append(".")
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
