package com.lazyeng.family.feature.player

/** Projects legacy bilingual SRT cue bodies without changing stored subtitle versions. */
fun splitSubtitleLanguages(rawText: String): Pair<String, String?> {
    val text = rawText.trim()
    val firstHan = text.indexOfFirst { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    if (firstHan <= 0 || text.substring(0, firstHan).none { it in 'A'..'Z' || it in 'a'..'z' }) {
        return text.replace(Regex("\\s+"), " ") to null
    }
    var boundary = firstHan
    while (boundary > 0 && text[boundary - 1] in "“‘（【") boundary--
    val english = text.substring(0, boundary).trim().replace(Regex("\\s+"), " ")
    val chinese = text.substring(boundary).trim().replace(Regex("\\s*\\n\\s*"), "")
    return english to chinese.takeIf { it.isNotBlank() }
}
