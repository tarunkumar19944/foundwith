package app.foundwith.call

import android.app.Activity
import android.os.Bundle
import app.foundwith.ui.copyNumber
import app.foundwith.ui.searchInTruecaller

/**
 * The "Copy number" and "Truecaller" buttons on a call notification. It has no screen: it does the
 * one thing and closes. An activity (not a receiver) so Truecaller can open from it on newer Android.
 */
class NumberActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val e164 = intent.getStringExtra(Notifier.EXTRA_NUMBER)
        if (e164 != null) when (intent.action) {
            Notifier.ACTION_COPY -> copyNumber(e164)
            Notifier.ACTION_TRUECALLER -> searchInTruecaller(e164)
        }
        finish()
    }
}
