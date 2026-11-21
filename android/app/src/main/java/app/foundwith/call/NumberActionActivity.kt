package app.foundwith.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.foundwith.R
import app.foundwith.core.Messages
import app.foundwith.data.Invites
import app.foundwith.graph
import app.foundwith.ui.copyNumber
import app.foundwith.ui.searchInTruecaller
import app.foundwith.ui.shareImage
import kotlinx.coroutines.launch

/**
 * The buttons on notifications: "Copy number" and "Truecaller" on a call, "Share in group" on a
 * level. It has no screen: it does the one thing and closes. An activity (not a receiver) so
 * Truecaller and the share sheet can open from it on newer Android.
 */
class NumberActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val e164 = intent.getStringExtra(Notifier.EXTRA_NUMBER).orEmpty()
        when (intent.action) {
            Notifier.ACTION_COPY -> if (e164.isNotEmpty()) copyNumber(e164)
            Notifier.ACTION_TRUECALLER -> if (e164.isNotEmpty()) searchInTruecaller(e164)
            Notifier.ACTION_SHARE_GROUP -> {
                // The person's own invite link, from the cards kept on the phone.
                lifecycleScope.launch {
                    val link = runCatching { graph.store.cachedCards()?.circle?.invite?.link }.getOrNull() ?: Invites.PLAY_STORE
                    shareImage(R.drawable.share_whohas, Messages.groupShare(link), "Share WhoHas")
                    finish()
                }
                return
            }
        }
        finish()
    }
}
