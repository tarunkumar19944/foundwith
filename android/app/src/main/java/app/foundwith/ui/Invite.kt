package app.foundwith.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.foundwith.core.InviteCandidate
import app.foundwith.core.InviteList
import app.foundwith.core.Messages
import app.foundwith.core.NameSource
import app.foundwith.core.ShownName
import app.foundwith.data.Invites

/**
 * Invite friends: share your link anywhere, or invite the people you call most, one at a time.
 * Only people not on Found With are listed. Each Invite opens WhatsApp (or Messages) with the
 * message typed in, for the person to send.
 */
@Composable
fun InviteScreen(graph: AppGraph, onBack: () -> Unit) {
    val state by graph.model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var candidates by remember { mutableStateOf<List<InviteCandidate>>(emptyList()) }
    val link = state.cards?.circle?.invite?.link ?: Invites.PLAY_STORE
    val members = state.cards?.circle?.memberRefs.orEmpty().toSet()

    LaunchedEffect(state.contacts, members) {
        val onFoundWith = state.index.byRef.filterKeys { it in members }.values.map { it.e164 }.toSet()
        candidates = InviteList.rank(graph.book.contactsForInvite(), graph.book.callLogCalls(), onFoundWith).take(100)
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar(onBack) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Invite friends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("When a friend joins, you see if they have your number.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton("Share your link", { context.shareText(Messages.inviteLink(link)) })
            }
        }
        if (candidates.isNotEmpty()) {
            item { SectionLabel("People you call most", Modifier.padding(top = 12.dp)) }
            items(candidates, key = { it.e164 }) { person ->
                val card = state.cardFor(person.e164)
                NumberRow(
                    leading = { Avatar(person.e164, person.name) },
                    name = ShownName(person.name, NameSource.CONTACT),
                    line = state.lineFor(card, onYourPhone = true),
                    trailing = {
                        OutlinedButton(onClick = { context.messagePerson(person.e164, Messages.invite(card?.total ?: 0, link)) }) {
                            Text("Invite")
                        }
                    },
                )
            }
        }
    }
}
