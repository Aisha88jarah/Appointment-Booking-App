package com.example.bookings.core

object TextUtil {
    /** Removes diacritics, tatweel, bidi/zero-width marks; Arabic-Indic digits -> ASCII; collapses spaces. */
    fun clean(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw.replace("\r\n", "\n")) {
            when {
                c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640' -> {}
                c == '\u200B' || c == '\u200E' || c == '\u200F' || c == '\uFEFF' -> {}
                c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' -> {}
                c in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                c in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                c == '\u00A0' || c == '\t' || c == '\u3000' -> sb.append(' ')
                c == '\r' -> sb.append('\n')
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ")
    }

    /** Length-preserving matching key: alef/teh-marbuta/yeh variants unified, ASCII lower-cased. */
    fun key(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    in 'A'..'Z' -> c + 32
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    fun collapse(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    fun trimValue(s: String): String {
        var v = s.trim { it.isWhitespace() || it in ",،;؛|.-–—\"'“”«»*_" }
        while (v.length >= 2 && ((v.first() == '(' && v.last() == ')') || (v.first() == '[' && v.last() == ']'))) {
            v = v.substring(1, v.length - 1).trim()
        }
        return v
    }

    /** Normalised form used to compare names / services. */
    fun norm(s: String): String = collapse(key(clean(s)))
}
