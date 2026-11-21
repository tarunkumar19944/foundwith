package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountTextTest {
    @Test
    fun theDialogListsWhatGoes() {
        assertEquals("This removes, from WhoHas:\n• Your number and name\n• Your contacts and call log numbers\n• Your blocked list and settings\n\n" +
            "Other people’s counts go down by one. Your phone’s own contacts and call log are not touched. You can’t undo this.",
            AccountText.deleteDialog(callLog = true))
    }

    @Test
    fun theContactsOnlyVersionNeverMentionsTheCallLog() {
        assertFalse(AccountText.deleteDialog(callLog = false).contains("call log"))
        assertFalse(AccountText.deleted(callLog = false).contains("call log"))
    }
}
