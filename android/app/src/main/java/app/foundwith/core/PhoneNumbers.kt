package app.foundwith.core

import java.text.Normalizer

/**
 * A copy of the server's number rules (backend/foundwith/numbers.py).
 *
 * The app always sends numbers exactly as they are saved; the server normalises them for
 * matching. The app normalises only to recognise its own numbers in the server's replies
 * (which are keyed by E.164), for example to put the right contact name on a card.
 * The parity test checks this file against cases generated from the Python code.
 */
object PhoneNumbers {
    private const val MAX_RAW_LENGTH = 64
    private const val PUNCTUATION = "()./"
    private val TOLL_FREE = listOf("1800", "1860")
    private val ASCII_NUMBER = Regex("""\+?[0-9]+""")
    private val INDIAN_MOBILE = Regex("""\+91[6-9][0-9]{9}""")

    /** Reduce a saved number to ASCII digits with an optional plus sign, or null if it has
     *  anything else in it (letters, "*", "#", pause characters before an extension). */
    fun clean(raw: String?): String? {
        if (raw == null || raw.length > MAX_RAW_LENGTH) return null
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp in '0'.code..'9'.code || cp == '+'.code -> out.appendCodePoint(cp)
                type == Character.DECIMAL_DIGIT_NUMBER.toInt() -> out.append(Character.digit(cp, 10))
                cp < 0x80 && PUNCTUATION.indexOf(cp.toChar()) >= 0 -> Unit
                Character.isWhitespace(cp) -> Unit
                type == Character.SPACE_SEPARATOR.toInt() ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.DASH_PUNCTUATION.toInt() -> Unit
                else -> return null
            }
        }
        return out.toString()
    }

    /** The number in E.164 form ("+919845031207"), or null if it can't be matched. */
    fun normalise(raw: String?, countryCode: String = "91"): String? {
        var s = clean(raw) ?: return null
        if (s.isEmpty()) return null
        if (s.startsWith("00")) s = "+" + s.substring(2)
        if (!ASCII_NUMBER.matches(s)) return null

        if (s.startsWith("+")) {
            val digits = s.substring(1)
            if (digits.startsWith(countryCode)) {
                return national(digits.substring(countryCode.length), countryCode, allowTrunk = true)
            }
            if (digits.startsWith("0") || digits.length !in 8..15) return null
            return "+$digits"
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

    fun isIndianMobile(e164: String?): Boolean = e164 != null && INDIAN_MOBILE.matches(e164)

    /** "+91 98450 31207" for Indian numbers, the E.164 string otherwise. */
    fun display(e164: String): String =
        if (e164.startsWith("+91") && e164.length == 13) {
            "+91 ${e164.substring(3, 8)} ${e164.substring(8)}"
        } else {
            e164
        }
}
