package app.foundwith.core

import java.text.Normalizer

/**
 * A copy of the server's number rules (backend/foundwith/numbers.py). WhoHas checks Indian
 * (+91) numbers only.
 *
 * The app always sends numbers exactly as they are saved; the server normalises them for
 * matching. The app normalises only to recognise its own numbers in the server's replies
 * (which are keyed by E.164), for example to put the right contact name on a card.
 * The parity test checks this file against cases generated from the Python code.
 */
object PhoneNumbers {
    /** Goes up whenever these rules change, so every phone sends its numbers again once. */
    const val RULES = 2
    private const val MAX_RAW_LENGTH = 64
    private const val INSIDE = "()."
    private val TOLL_FREE = listOf("1800", "1860")
    private val ASCII_NUMBER = Regex("""\+?[0-9]+""")
    private val MOBILE_SHAPE = Regex("""\+91[6-9][0-9]{9}""")
    private val INDIA_MOBILE = Regex(IndiaMobile.PATTERN)

    private fun isDigit(cp: Int) = cp in '0'.code..'9'.code || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()

    private fun continues(cp: Int): Boolean {
        if (isDigit(cp) || Character.isWhitespace(cp)) return true
        if (cp < 0x80 && INSIDE.indexOf(cp.toChar()) >= 0) return true
        val type = Character.getType(cp)
        return type == Character.FORMAT.toInt() || type == Character.DASH_PUNCTUATION.toInt() || type == Character.SPACE_SEPARATOR.toInt()
    }

    /** Each stretch of the text that could be one phone number, in order. A stretch starts at a
     *  digit or "+" and runs through digits, spaces, dashes, brackets, dots and invisible marks;
     *  anything else ends it (letters, "/", ",", ";", "*", "#"). */
    private fun runs(raw: String): List<String> {
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val out = ArrayList<String>()
        val run = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (run.isNotEmpty() && continues(cp)) {
                run.appendCodePoint(cp)
                continue
            }
            if (run.isNotEmpty()) {
                out += run.toString()
                run.setLength(0)
            }
            if (isDigit(cp) || cp == '+'.code) run.appendCodePoint(cp)
        }
        if (run.isNotEmpty()) out += run.toString()
        return out
    }

    /** One stretch reduced to ASCII digits with an optional plus sign. */
    private fun ascii(run: String): String {
        val out = StringBuilder(run.length)
        var i = 0
        while (i < run.length) {
            val cp = run.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp in '0'.code..'9'.code || cp == '+'.code -> out.appendCodePoint(cp)
                Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() -> out.append(Character.digit(cp, 10))
            }
        }
        return out.toString()
    }

    /** The digits of the first number in a field, for search: "98450 31207" -> "9845031207". */
    fun clean(raw: String?): String? {
        if (raw == null || raw.codePointCount(0, raw.length) > MAX_RAW_LENGTH) return null
        return runs(raw).firstOrNull()?.let(::ascii)
    }

    /** The Indian number in E.164 form ("+919845031207"), or null. The first full number in the
     *  field is used ("tel:+91 98450 31207", "080 4123 5566,204", "98450 31207 / 98450 31208");
     *  two numbers are never joined into one. Numbers of other countries are null. */
    fun normalise(raw: String?, countryCode: String = "91"): String? {
        if (raw == null || raw.codePointCount(0, raw.length) > MAX_RAW_LENGTH) return null
        for (run in runs(raw)) one(ascii(run), countryCode)?.let { return it }
        return null
    }

    /** True when the field holds another country's number (and no Indian one before it), so the
     *  app can say "WhoHas checks only Indian numbers" instead of nothing. */
    fun isForeign(raw: String?, countryCode: String = "91"): Boolean {
        if (raw == null || raw.codePointCount(0, raw.length) > MAX_RAW_LENGTH) return false
        for (run in runs(raw)) {
            var s = ascii(run)
            if (one(s, countryCode) != null) return false
            if (s.startsWith("00")) s = "+" + s.substring(2)
            if (s.startsWith("+") && ASCII_NUMBER.matches(s)) {
                val digits = s.substring(1)
                if (!digits.startsWith(countryCode) && !digits.startsWith("0") && digits.length in 8..15) return true
            }
        }
        return false
    }

    private fun one(sIn: String, countryCode: String): String? {
        var s = sIn
        if (s.isEmpty()) return null
        if (s.startsWith("00")) s = "+" + s.substring(2)
        if (!ASCII_NUMBER.matches(s)) return null

        if (s.startsWith("+")) {
            val digits = s.substring(1)
            if (!digits.startsWith(countryCode)) return null                 // another country
            var rest = digits.substring(countryCode.length)
            if (rest.startsWith(countryCode) && rest.length == countryCode.length + 10) {
                rest = rest.substring(countryCode.length)                    // +91 91 98450 31207
            }
            return national(rest, countryCode, allowTrunk = true)
        }

        var digits = s
        if (digits.startsWith("0$countryCode") && digits.length >= 13) digits = digits.substring(1)
        if (digits.startsWith(countryCode) && digits.length == countryCode.length + 10) {
            return national(digits.substring(countryCode.length), countryCode, allowTrunk = false)
        }
        return national(digits, countryCode, allowTrunk = true)
    }

    private fun national(nsnIn: String, countryCode: String, allowTrunk: Boolean): String? {
        var nsn = nsnIn
        if (allowTrunk && nsn.startsWith("0")) nsn = nsn.substring(1)
        if (TOLL_FREE.any { nsn.startsWith(it) } && nsn.length in 10..11) return "+$countryCode$nsn"
        if (nsn.length == 10 && nsn[0] != '0') return "+$countryCode$nsn"
        return null
    }

    /** True only for India's mobile ranges (Google's data): a Bengaluru landline (+91 80 4123
     *  5566) starts with 8 but is not a mobile. Use it where WhatsApp or an SMS must reach the number. */
    fun isIndianMobile(e164: String?): Boolean =
        e164 != null && e164.length == 13 && e164.startsWith("+91") && INDIA_MOBILE.matches(e164.substring(3))

    /** 10 digits starting with 6-9: the loose check for search and sign-in, so a newly opened
     *  mobile series still works before Google's data knows it. */
    fun isMobileShaped(e164: String?): Boolean = e164 != null && MOBILE_SHAPE.matches(e164)

    /** "+91 98450 31207" for Indian numbers, the E.164 string otherwise. */
    fun display(e164: String): String =
        if (e164.startsWith("+91") && e164.length == 13) {
            "+91 ${e164.substring(3, 8)} ${e164.substring(8)}"
        } else {
            e164
        }
}
