package com.ichi2.anki.ranking

fun sanitizeRankingLookupTerm(
    raw: String,
    removeNonAlphabeticChars: Boolean,
): String {
    if (!removeNonAlphabeticChars) {
        return raw
    }

    val builder = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val codePoint = raw.codePointAt(index)
        if (Character.isLetter(codePoint)) {
            builder.appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
    }
    return builder.toString()
}
