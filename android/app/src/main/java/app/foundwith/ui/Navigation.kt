package app.foundwith.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.foundwith.AppGraph
import app.foundwith.data.Gate
import kotlinx.coroutines.flow.Flow

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val NUMBER = "number/{e164}"
    const val INVITE = "invite"
    const val SETTINGS = "settings"
    const val NOTIFICATIONS = "settings/notifications"
    const val NAME = "settings/name"
    const val DATA = "settings/data"
    const val BLOCKED = "settings/blocked"
    const val HELP = "help"
    const val HOW = "how-it-works"
    const val FIX = "phone-fix"

    fun number(e164: String) = "number/${android.net.Uri.encode(e164)}"
}

/**
 * The whole app. Sign-in goes straight to Home; everything else is one or two taps from there,
 * and Back always returns to where the person was. [openNumber] carries numbers from notifications.
 */
@Composable
fun AppRoot(graph: AppGraph, openNumber: Flow<String?>) {
    val nav = rememberNavController()
    val state by graph.model.state.collectAsStateWithLifecycle()
    val start = remember { if (graph.auth.isSignedIn) Routes.HOME else Routes.LOGIN }

    LaunchedEffect(Unit) {
        openNumber.collect { e164 ->
            if (e164 == null) return@collect
            if (e164 == UPDATES) {
                graph.openUpdates.value = true
                nav.startOver(Routes.HOME)
            }
            else if (graph.auth.isSignedIn) nav.navigate(Routes.number(e164)) { launchSingleTop = true }
        }
    }

    when (state.gate) {
        Gate.UPDATE_NEEDED -> return UpdateNeededScreen()
        Gate.SUSPENDED -> return SuspendedScreen(graph, onSignedOut = { nav.startOver(Routes.LOGIN) })
        Gate.NONE -> Unit
    }

    // Phones use the full width. On tablets, foldables and landscape the app stays a readable
    // column in the middle, and it keeps clear of camera cut-outs at the sides.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        NavHost(navController = nav, startDestination = start, modifier = Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            composable(Routes.LOGIN) {
                LoginScreen(graph, onDone = { nav.startOver(Routes.HOME) })
            }
            composable(Routes.HOME) {
                // Until the saved data is read, plain charcoal (the opening screen covers it), never a
                // first-time Home that then changes.
                if (!state.loaded) { Box(Modifier.fillMaxSize()); return@composable }
                HomeScreen(
                    graph,
                    onNumber = { nav.navigate(Routes.number(it)) { launchSingleTop = true } },
                    onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onInvite = { nav.navigate(Routes.INVITE) { launchSingleTop = true } },
                    onHowItWorks = { nav.navigate(Routes.HOW) { launchSingleTop = true } },
                    onFix = { nav.navigate(Routes.FIX) { launchSingleTop = true } },
                )
            }
            composable(Routes.NUMBER, arguments = listOf(navArgument("e164") { type = NavType.StringType })) { entry ->
                val e164 = entry.arguments?.getString("e164").orEmpty()
                CardScreen(graph, e164, onBack = { nav.popBackStack() },
                    onNumber = { nav.navigate(Routes.number(it)) },
                    onInviteFriends = { nav.navigate(Routes.INVITE) { launchSingleTop = true } })
            }
            composable(Routes.INVITE) { InviteScreen(graph, onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(graph, onBack = { nav.popBackStack() },
                    onPage = { page -> nav.navigate("settings/$page".replace("settings/help", Routes.HELP)) { launchSingleTop = true } },
                    onInvite = { nav.navigate(Routes.INVITE) { launchSingleTop = true } })
            }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(graph, onBack = { nav.popBackStack() }, onFix = { nav.navigate(Routes.FIX) })
            }
            composable(Routes.NAME) { NameScreen(graph, onBack = { nav.popBackStack() }) }
            composable(Routes.DATA) {
                DataScreen(graph, onBack = { nav.popBackStack() }, onBlocked = { nav.navigate(Routes.BLOCKED) },
                    onSignedOut = { nav.startOver(Routes.LOGIN) }, onDeleted = { nav.startOver(Routes.LOGIN) })
            }
            composable(Routes.BLOCKED) { BlockedScreen(graph, onBack = { nav.popBackStack() }) }
            composable(Routes.HELP) { HelpScreen(onBack = { nav.popBackStack() }, onHowItWorks = { nav.navigate(Routes.HOW) }) }
            composable(Routes.HOW) { HowItWorksScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.FIX) { PhoneFixScreen(graph, onBack = { nav.popBackStack() }) }
        }
    }
}

/** Sent through [AppRoot]'s openNumber to show Updates (the evening note). */
const val UPDATES = "updates"

/** Opens [route] with nothing behind it, so Back leaves the app instead of returning to a stale screen. */
private fun NavHostController.startOver(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
