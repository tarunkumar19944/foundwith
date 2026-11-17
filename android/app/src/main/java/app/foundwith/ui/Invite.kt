package app.foundwith.ui

import kotlinx.coroutines.launch

import app.foundwith.core.TellList

import app.foundwith.core.TellCandidate

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.material.icons.filled.Share

import androidx.compose.material.icons.Icons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foundwith.AppGraph
import app.foundwith.core.InviteList
import app.foundwith.core.Messages
import app.foundwith.data.Invites

/**
 * Invite friends: share your link anywhere, or tell the people you call most, one at a time, how
 * many people on WhoHas have their number (the real count). Only people not on WhoHas are listed.
 * Each Tell opens WhatsApp (or Messages) with the message typed in, for the person to send.
 */
@Composable
fun InviteScreen(graph: AppGraph, onBack: () -> Unit) {
    val state by graph.model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<TellCandidate>>(emptyList()) }
    var told by remember { mutableStateOf(setOf<String>()) }
    val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE
    val members = state.cards?.circle?.memberRefs.orEmpty().toSet()

    LaunchedEffect(state.contacts, members) {
        val onWhoHas = state.index.byRef.filterKeys { it in members }.values.map { it.e164 }.toSet()
        val recent = graph.store.toldAt().filterValues { System.currentTimeMillis() - it < TellList.TOLD_DAYS * 24 * 3600_000L }.keys
        candidates = InviteList.rank(graph.book.contactsForInvite(), graph.book.callLogCalls(), onWhoHas).take(100)
            .map { c -> val card = state.cardFor(c.e164); TellCandidate(c.e164, c.name, c.calls, card?.total ?: 0, card?.totalIsLowerBound == true) }
            .sortedBy { it.e164 in recent }   // people told lately go to the end
        told = recent
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar(onBack) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Invite friends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("When they join, you'll see if they have your number.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton("Share in a group", {
                    context.shareImage(app.foundwith.R.drawable.share_whohas, Messages.groupShare(link), "Share WhoHas")
                }, icon = Icons.Filled.Share)
            }
        }
        if (candidates.isNotEmpty()) {
            item { SectionLabel("People you call most", Modifier.padding(top = 12.dp)) }
            items(candidates, key = { it.e164 }) { person ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    TellRow(person, told = person.e164 in told) {
                        context.messagePerson(person.e164, Messages.invite(person.others, link, person.lowerBound))
                        told = told + person.e164
                        scope.launch { graph.model.recordTell(person.e164) }
                    }
                }
            }
            if (told.isNotEmpty()) item {
                Text("People you told leave the Tell card for 30 days.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}
