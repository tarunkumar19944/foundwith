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

    @Test
    fun tellListTakesRegularCallersAndShowsTheBiggestRealCountFirst() {
        val day = 24L * 3600_000
        val now = 200 * day
        val contacts = listOf(
            PhoneContact("98450 11111", "Ravi Kumar"),
            PhoneContact("98450 22222", "Amma"),
            PhoneContact("98450 33333", "Once Only"),
            PhoneContact("98450 44444", "Old Friend"),
            PhoneContact("98450 55555", "On WhoHas"),
            PhoneContact("98450 66666", "Told Last Week"),
        )
        fun calls(raw: String, n: Int, daysAgo: Long) = List(n) { raw to now - daysAgo * day }
        val log = calls("9845011111", 3, 2) + calls("9845022222", 9, 1) + calls("9845033333", 1, 1) +
            calls("9845044444", 5, 90) + calls("9845055555", 6, 1) + calls("9845066666", 4, 3)
        val counts = mapOf("+919845011111" to (7 to false), "+919845022222" to (5 to false))
        val picked = TellList.pick(contacts, log, onWhoHas = setOf("+919845055555"), counts = counts,
            toldAt = mapOf("+919845066666" to now - 7 * day), now = now)
        assertEquals(listOf("Ravi Kumar", "Amma"), picked.map { it.name })   // 7 people before 5, though Amma is called more
        assertEquals(7, picked.first().others)
        val toldLongAgo = TellList.pick(contacts, log, setOf("+919845055555"), counts, mapOf("+919845066666" to now - 31 * day), now)
        assertTrue(toldLongAgo.any { it.name == "Told Last Week" })
        assertTrue(TellList.hidden(now - 6 * day, now))
        assertTrue(!TellList.hidden(now - 8 * day, now))
        assertTrue(!TellList.hidden(0, now))
    }
}
