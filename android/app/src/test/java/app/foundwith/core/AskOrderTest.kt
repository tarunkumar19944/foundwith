package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AskOrderTest {
    @Test
    fun talkMostThenFavouritesThenContactsThenCallsOnly() {
        val people = listOf(
            KnownPerson("1", "Zoya", null, inContacts = true),
            KnownPerson("2", "Naveen", null, inContacts = false, calls = 1),
            KnownPerson("3", "Priya (Office)", null, inContacts = true, calls = 9),
            KnownPerson("4", "Meena", null, inContacts = true, starred = true),
            KnownPerson("5", "anil", null, inContacts = true, calls = 2),
            KnownPerson("6", "Ravi Kumar", null, inContacts = true, calls = 4),
            KnownPerson("3", "Priya (Office)", null, inContacts = true, calls = 9),   // twice: shown once
        )
        val ordered = AskOrder.order(people)
        assertEquals(listOf("Priya (Office)", "Ravi Kumar", "Meena", "anil", "Zoya", "Naveen"), ordered.map { it.first.name })
        assertEquals(listOf(AskWhy.TALK_OFTEN, AskWhy.TALK_OFTEN, AskWhy.FAVOURITE, AskWhy.CONTACT, AskWhy.CONTACT, AskWhy.FROM_CALLS),
            ordered.map { it.second })
        assertEquals("In your call log", AskWhy.FROM_CALLS.hint)
    }
}
