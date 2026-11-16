package app.foundwith.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foundwith.AppGraph
import app.foundwith.core.PhoneNumbers
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where sign-in stands. Each has its own screen (design screens 1 to 11). */
enum class LoginStep { WELCOME, CHECKING, TYPE_NUMBER, CODE, TOO_MANY, NO_INTERNET }

/**
 * Sign-in is one tap: Android's own sheet offers the phone's numbers (both, on a dual-SIM phone),
 * the SMS code is read by itself, and the person lands on Home. Picking a number only fills it in:
 * the code proves it, and it only ever goes to the number itself.
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
    /** The sheet opens by itself once, on the first screen. */
    var sheetShown = false

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var onSignedIn: (() -> Unit)? = null

    fun bind(done: () -> Unit) {
        onSignedIn = done
    }

    /** The sheet was closed, offered no number, or the person wants a different one. */
    fun typeNumber() {
        error = null
        step = LoginStep.TYPE_NUMBER
    }

    fun picked(activity: Activity?, raw: String) {
        number = PhoneNumbers.normalise(raw)?.let { PhoneNumbers.display(it).removePrefix("+91 ") } ?: raw
        sendCode(activity)
    }

    fun sendCode(activity: Activity?) {
        val parsed = PhoneNumbers.normalise(number)
        if (parsed == null || !PhoneNumbers.isIndianMobile(parsed)) {
            error = "10 digits, starting with 6, 7, 8 or 9."
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
        graph.auth.sendCode(activity, parsed, resendToken, callbacks)
    }

    private val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            // Android read the code from the SMS by itself.
            signIn { graph.auth.signIn(credential) }
        }

        override fun onVerificationFailed(e: FirebaseException) {
            busy = false
            when (e) {
                is FirebaseTooManyRequestsException -> step = LoginStep.TOO_MANY
                is FirebaseNetworkException -> step = LoginStep.NO_INTERNET
                is FirebaseAuthInvalidCredentialsException -> {
                    error = "10 digits, starting with 6, 7, 8 or 9."
                    step = LoginStep.TYPE_NUMBER
                }
                else -> {
                    error = "We couldn't send the code. Try again."
                    step = LoginStep.TYPE_NUMBER
                }
            }
        }

        override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
            busy = false
            verificationId = id
            resendToken = token
            // Keep "Checking…" while Android may still read the SMS; the code screen comes after a moment.
            viewModelScope.launch {
                delay(6000)
                if (step == LoginStep.CHECKING) step = LoginStep.CODE
            }
            startResendTimer()
        }

        override fun onCodeAutoRetrievalTimeOut(id: String) {
            if (step == LoginStep.CHECKING) step = LoginStep.CODE
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

    fun verifyCode() {
        val id = verificationId ?: return
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
                error = "That code doesn't match. Check the SMS."
                step = LoginStep.CODE
            } catch (e: FirebaseTooManyRequestsException) {
                step = LoginStep.TOO_MANY
            } catch (e: FirebaseNetworkException) {
                step = LoginStep.NO_INTERNET
            } catch (e: Exception) {
                error = "Something went wrong. Try again."
                step = LoginStep.CODE
            } finally {
                busy = false
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
}

@Composable
fun LoginScreen(graph: AppGraph, onDone: () -> Unit) {
    val vm = appViewModel { LoginViewModel(graph) }
    vm.bind(onDone)
    val context = LocalContext.current
    val activity = context.findActivity()
    val deleted = rememberSaveable { graph.justDeleted.also { graph.justDeleted = false } }
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
    LaunchedEffect(Unit) {
        if (vm.step == LoginStep.WELCOME && !vm.sheetShown && !deleted) {
            vm.sheetShown = true
            openSheet()
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (vm.step) {
            LoginStep.WELCOME -> {
                if (invited) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                        Text("A friend invited you", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Brand()
                if (deleted) {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text("Your account is deleted.")
                    }
                } else {
                    Example()
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Continue", { openSheet() })
                TextButton(onClick = { vm.typeNumber() }) { Text("Type your number") }
            }
            LoginStep.CHECKING -> {
                Brand(small = true)
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Checking ${vm.e164?.let { shown(it) }.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                    Text("Reading the code from the SMS…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LoginStep.TYPE_NUMBER -> {
                Text("Your mobile number", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                    Text("Sent to ${vm.e164?.let { shown(it) }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.changeNumber() }) { Text("Change") }
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
                    Text("Resend in 0:${vm.resendIn.toString().padStart(2, '0')}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    PrimaryButton("Resend code", { vm.sendCode(activity) }, enabled = !vm.busy)
                }
            }
            LoginStep.TOO_MANY -> {
                Brand(small = true)
                EmptyState(Icons.Filled.Warning, "Too many tries", "For safety, wait a while and try again.")
                OutlinedButton(onClick = { vm.typeNumber() }, modifier = Modifier.fillMaxWidth()) { Text("OK") }
            }
            LoginStep.NO_INTERNET -> {
                Brand(small = true)
                EmptyState(Icons.Filled.Warning, "No internet", "Connect and try again.", button = "Try again") { vm.tryAgain(activity) }
            }
        }
    }
}

@Composable
private fun Brand(small: Boolean = false) {
    Column {
        Text("Found With", style = if (small) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        if (!small) Text("See who has your number.", style = MaterialTheme.typography.bodyLarge)
    }
}

/** What the app does, shown before anything is asked: "Your number · Found with Priya, Ravi and 21 others". */
@Composable
private fun Example() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("For example", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your number", fontWeight = FontWeight.SemiBold)
            Row { Avatar("p", "Priya S", 24.dp); Spacer(Modifier.width(4.dp)); Avatar("r", "Ravi Kumar", 24.dp) }
            Text("Found with Priya, Ravi and 21 others", color = MaterialTheme.colorScheme.primary)
            Text("23 people have your number", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.width(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("Never messages or calls your contacts.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
