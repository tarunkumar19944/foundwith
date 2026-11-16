package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteListTest {
    @Test
    fun mostCalledFirstThenFavouritesThenName() {
        val contacts = listOf(
            PhoneContact("98450 11111", "Zoya"),
            PhoneContact("+91 98450 22222", "anil"),
            PhoneContact("09845033333", "Bala", starred = true),
            PhoneContact("98450 44444", "Chitra"),
        )
        val calls = listOf("9845044444", "+919845044444", "9845011111")
        val ranked = InviteList.rank(contacts, calls, onFoundWith = emptySet())
        assertEquals(listOf("Chitra", "Zoya", "Bala", "anil"), ranked.map { it.name })
        assertEquals(2, ranked.first().calls)
    }

    @Test
    fun leavesOutMembersLandlinesAndDuplicates() {
        val contacts = listOf(
            PhoneContact("98450 11111", "Ravi"),
            PhoneContact("+919845011111", "Ravi (work)", starred = true),
            PhoneContact("044 2222 3333", "Office landline"),
            PhoneContact("98450 55555", "Already here"),
            PhoneContact("12345", "Short code"),
            PhoneContact("98450 66666", "  "),
        )
        val ranked = InviteList.rank(contacts, emptyList(), onFoundWith = setOf("+919845055555"))
        assertEquals(listOf("Ravi", "+91 98450 66666"), ranked.map { it.name })
        assertTrue("a favourite under either name counts", ranked.first().starred)
    }

    @Test
    fun readsTheInviteCodeFromThePlayReferrer() {
        assertEquals("ABCD2345", InviteList.codeFrom("invite=ABCD2345"))
        assertEquals("ABCD2345", InviteList.codeFrom("utm_source=x&invite=abcd2345"))
        assertNull(InviteList.codeFrom("utm_source=google-play&utm_medium=organic"))
        assertNull(InviteList.codeFrom("invite=ABCD234"))       // too short
        assertNull(InviteList.codeFrom("invite=ABCD23450"))     // too long
        assertNull(InviteList.codeFrom("invite=ABCDI234"))      // I and O are never used
        assertNull(InviteList.codeFrom(null))
    }

    @Test
    fun firstNameIsOnlyAWordWithLetters() {
        assertEquals("Ravi", InviteList.firstName("Ravi Kumar"))
        assertNull(InviteList.firstName("+91 98450 66666"))
    }
}
