package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MessagesTest {
    private val link = "https://found-with.app/i/A7KQ2M"

    @Test
    fun askNeverGreetsByNameAndPutsTheNumberOnItsOwnLine() {
        assertEquals("Hi, do you know whose number this is?\n+91 77609 34418", Messages.ask("+917760934418"))
        assertEquals("Hi, do you know whose number this is?\n+91 80951 22764\nIt shows as Mahesh Electricals.",
            Messages.ask("+918095122764", popularName = "Mahesh Electricals"))
        assertEquals("Hi, do you know Deepa Rao?\n+91 99015 66420", Messages.ask("+919901566420", ownerName = "Deepa Rao"))
    }

    @Test
    fun inviteCountsOtherPeopleNeverTheSender() {
        assertEquals("Hi, I'm using Found With. 13 other people on Found With have your number too. " +
            "See which of them you know:\n$link", Messages.invite(13, link))
        assertEquals("Hi, I'm using Found With. 1 other person on Found With has your number too. " +
            "See if you know them:\n$link", Messages.invite(1, link))
        assertEquals("Hi, I'm using Found With. When an unknown number calls, it shows which people you know " +
            "have that number. Join here:\n$link", Messages.invite(0, link))
        assertEquals("I'm using Found With. When an unknown number calls, it shows which people you know " +
            "have that number. Join here:\n$link", Messages.inviteLink(link))
        assertEquals(true, Messages.invite(1_20_000, link).contains("1,20,000 other people"))
    }

    @Test
    fun shareGivesTheNumberAndACountButNoNames() {
        assertEquals("Mahesh Electricals\n+91 80951 22764\n\n9 other people on Found With have this number too. " +
            "See which of them you know:\n$link", Messages.share("Mahesh Electricals", "+918095122764", 9, link))
        assertEquals("Raju Carpenter\n+91 90088 41276\n\nSee who has any number:\n$link",
            Messages.share("Raju Carpenter", "+919008841276", 0, link))
        assertEquals("+91 77609 34418\n\n1 other person on Found With has this number too. See if you know them:\n$link",
            Messages.share("+91 77609 34418", "+917760934418", 1, link))
        assertFalse(Messages.share(null, "+917760934418", 5, link).startsWith("null"))
    }
}
