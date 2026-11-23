package app.foundwith.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Surface
import app.foundwith.core.HolderText
import app.foundwith.core.LinkJoins
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
    // How many could add to your count (23 Nov 2026): people you call who aren't on WhoHas, else your contacts who aren't.
    var reach by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE
    val members = state.cards?.circle?.memberRefs.orEmpty().toSet()

    LaunchedEffect(state.contacts, members) {
        val onWhoHas = state.index.byRef.filterKeys { it in members }.values.map { it.e164 }.toSet()
        val recent = graph.store.toldAt().filterValues { System.currentTimeMillis() - it < TellList.TOLD_DAYS * 24 * 3600_000L }.keys
        val ranked = InviteList.rank(graph.book.contactsForInvite(), graph.book.callLogCalls(), onWhoHas)
        val called = ranked.count { it.calls > 0 }
        reach = if (called > 0) called to true else ranked.size to false
        candidates = ranked.take(100)
            .map { c -> val card = state.cardFor(c.e164); TellCandidate(c.e164, c.name, c.calls, card?.total ?: 0, card?.totalIsLowerBound == true) }
            .sortedBy { it.e164 in recent }   // people told lately go to the end
        told = recent
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar(onBack) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Invite friends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val count = reach?.first ?: 0
                val joins = state.linkJoins()
                if (count > 0) {
                    // "38 people you call aren't on WhoHas. Each one who joins can add 1 to your 8."
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(HolderText.grouped(count), fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(LinkJoins.reach(count, reach?.second == true).substringAfter(' '), style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            Text(LinkJoins.reachInvite(state.cards?.mine?.total ?: 0))
                            if (joins.isNotEmpty()) Text(LinkJoins.already(joins.map { it.name }), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("Adds the most value: the more of your group join, the more callers you can recognise.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton("Share in group", {
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
