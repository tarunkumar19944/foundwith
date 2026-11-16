package app.foundwith.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {
    private val e164 = "+917760934418"

    @Test
    fun yourNameThenTheirOwnThenPopularThenNumber() {
        assertEquals(ShownName("Kiran Gowda", NameSource.CONTACT), Naming.of("Kiran Gowda", "Kiran G", "Kiran", e164))
        assertEquals(ShownName("Deepa Rao", NameSource.THEIR_OWN), Naming.of(null, "Deepa Rao", "Deepa", e164))
        assertEquals(ShownName("Mahesh Electricals", NameSource.POPULAR), Naming.of(null, null, "Mahesh Electricals", e164))
        assertEquals(ShownName("+91 77609 34418", NameSource.NUMBER), Naming.of(null, null, null, e164))
    }

    @Test
    fun tagsSayWhereANameCameFrom() {
        assertEquals("their own name", Naming.of(null, "Deepa Rao", null, e164).tag)
        assertEquals("what others call it", Naming.of(null, null, "Mahesh", e164).tag)
        assertNull(Naming.of("Amma", null, null, e164).tag)
    }

    @Test
    fun aContactNameThatIsJustTheNumberIsNoName() {
        assertEquals(NameSource.THEIR_OWN, Naming.of("+91 77609 34418", "Deepa Rao", null, e164).source)
        assertTrue(Naming.of(" ", null, null, e164).isNumber)
        assertEquals("Unknown number", Naming.of(null, null, null, null, raw = "").text)
    }

    @Test
    fun namesAreShownExactlyAsSaved() {
        assertEquals("Dr. Anand", Naming.of("Dr. Anand", null, null, e164).text)
        assertEquals("Lakshmi Aunty", Naming.person("Lakshmi Aunty", "Lakshmi R", e164))
        assertEquals("Lakshmi R", Naming.person(null, "Lakshmi R", e164))
        assertEquals("+91 77609 34418", Naming.person(null, null, e164))
    }
}
