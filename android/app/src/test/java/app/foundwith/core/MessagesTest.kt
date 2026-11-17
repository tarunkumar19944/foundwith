package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MessagesTest {
    private val link = "https://whohas.app/i/A7KQ2M"

    @Test
    fun askNeverGreetsByNameAndPutsTheNumberOnItsOwnLine() {
        assertEquals("Hi, do you know whose number this is?\n+91 77609 34418", Messages.ask("+917760934418"))
        assertEquals("Hi, do you know whose number this is?\n+91 80951 22764\nSome people know it as \"Mahesh Electricals\".",
            Messages.ask("+918095122764", popularName = "Mahesh Electricals"))
        assertEquals("Hi, do you know Deepa Rao?\n+91 99015 66420", Messages.ask("+919901566420", ownerName = "Deepa Rao"))
    }

    @Test
    fun tellCountsOtherPeopleNeverTheSender() {
        assertEquals("Hi! I'm on WhoHas, and 13 more people there have your number. See who they are:\n$link", Messages.invite(13, link))
        assertEquals("Hi! I'm on WhoHas, and 1 more person there has your number. See who:\n$link", Messages.invite(1, link))
        assertEquals("Hi! I'm on WhoHas. If an unknown number calls either of us, we can see if the other knows them. " +
            "Join here:\n$link", Messages.invite(0, link))
        assertEquals(Messages.invite(0, link), Messages.inviteLink(link))
        assertEquals(true, Messages.invite(1_20_000, link).contains("1,20,000 more people"))
        assertEquals(true, Messages.invite(10_000, link, lowerBound = true).contains("10,000+ more people"))
    }

    @Test
    fun groupShareSaysWhatItDoesAndWhyTheGroupBenefits() {
        val text = Messages.groupShare(link)
        assertEquals(true, text.startsWith("Hi all! I'm using WhoHas. When an unknown number calls, it shows which people you know have that number"))
        assertEquals(true, text.contains("The more of us on it, the more callers we can recognise for each other."))
        assertEquals(true, text.endsWith("Get it here:\n$link"))
    }

    @Test
    fun shareGivesTheNumberAndACountButNoNames() {
        assertEquals("Mahesh Electricals\n+91 80951 22764\n\n9 other people on WhoHas have this number. See who:\n$link",
            Messages.share("Mahesh Electricals", "+918095122764", 9, link))
        assertEquals("Raju Carpenter\n+91 90088 41276\n\nSee who has any number on WhoHas:\n$link",
            Messages.share("Raju Carpenter", "+919008841276", 0, link))
        assertEquals("+91 77609 34418\n\n1 other person on WhoHas has this number. See who:\n$link",
            Messages.share("+91 77609 34418", "+917760934418", 1, link))
        assertFalse(Messages.share(null, "+917760934418", 5, link).startsWith("null"))
    }
}
