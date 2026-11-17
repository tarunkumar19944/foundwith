package app.foundwith.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun indianMobileFormatsAllMatch() {
        listOf(
            "9845031207", "98450 31207", "98450-31207", "+91 98450 31207", "+91-98450-31207",
            "(+91) 98450 31207", "09845031207", "919845031207", "0919845031207", "00919845031207",
            "+91 0 98450 31207", "098450 31207",
            "९८४५०३१२०७", "೯೮೪೫೦೩೧೨೦೭", "٩٨٤٥٠٣١٢٠٧", "＋９１ ９８４５０ ３１２０７",
            "‪+91 98450 31207‬", "98450‑31207", "98450–31207", "98450​31207",
        ).forEach { assertEquals(it, "+919845031207", PhoneNumbers.normalise(it)) }
    }

    @Test
    fun otherFormats() {
        mapOf(
            "080 4123 5566" to "+918041235566",
            "011-2345-6789" to "+911123456789",
            "1800 425 1234" to "+9118004251234",
            "9198450312" to "+919198450312",
            "+91 91 98450 31207" to "+919845031207",
            "tel:+91 98450 31207" to "+919845031207",
            "9845031207 (home)" to "+919845031207",
            "080 4123 5566,204" to "+918041235566",
            "080 4123 5566 ext 204" to "+918041235566",
            "98450 31207 / 98450 31208" to "+919845031207",
        ).forEach { (raw, e164) -> assertEquals(raw, e164, PhoneNumbers.normalise(raw)) }
    }

    @Test
    fun onlyIndianNumbers() {
        listOf("+1 (415) 555-0100", "00 44 20 7946 0958", "+971 50 123 4567").forEach {
            assertNull(it, PhoneNumbers.normalise(it))
            assertTrue(it, PhoneNumbers.isForeign(it))
        }
        assertFalse(PhoneNumbers.isForeign("98450 31207"))
        assertFalse(PhoneNumbers.isForeign("121"))
        assertFalse(PhoneNumbers.isForeign("971501234567"))   // no + or 00: can't tell, so not called foreign
    }

    @Test
    fun unmatchableNumbersAreNull() {
        listOf("", "   ", null, "121", "*121#", "41235566", "+91 12345", "+0123456789", "98450 3120",
            "call me", "+12345", "-1", "080-4123/4124", "98450/31207")
            .forEach { assertNull(it.toString(), PhoneNumbers.normalise(it)) }
    }

    @Test
    fun mobileCheckAndDisplay() {
        assertTrue(PhoneNumbers.isIndianMobile("+916366109284"))
        assertFalse(PhoneNumbers.isIndianMobile("+911123456789"))
        assertFalse(PhoneNumbers.isIndianMobile("+918041235566"))   // Bengaluru landline
        assertTrue(PhoneNumbers.isMobileShaped("+918041235566"))
        assertFalse(PhoneNumbers.isIndianMobile(null))
        assertEquals("+91 98450 31207", PhoneNumbers.display("+919845031207"))
        assertEquals("+14155550100", PhoneNumbers.display("+14155550100"))
    }

    /** Every case in number_parity.jsonl was produced by the server's own normaliser. */
    @Test
    fun matchesTheServerOnThousandsOfGeneratedNumbers() {
        val stream = javaClass.classLoader!!.getResourceAsStream("number_parity.jsonl")
            ?: error("number_parity.jsonl is missing from the test resources")
        var checked = 0
        val mismatches = mutableListOf<String>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val row = Json.parseToJsonElement(line) as JsonArray
                val raw = row[0].jsonPrimitive.content
                val expected = if (row[1] is JsonNull) null else row[1].jsonPrimitive.content
                val mobile = row[2].jsonPrimitive.boolean
                val actual = PhoneNumbers.normalise(raw)
                if (actual != expected || PhoneNumbers.isIndianMobile(actual) != mobile) {
                    mismatches += "${Json.encodeToString(JsonArray.serializer(), row)} -> $actual"
                }
                checked++
            }
        }
        assertTrue("only $checked cases read", checked > 2000)
        assertTrue("${mismatches.size} mismatches, first: ${mismatches.take(5)}", mismatches.isEmpty())
    }
}
