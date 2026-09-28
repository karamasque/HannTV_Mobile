package tv.own.owntv.core.player

import java.util.Locale

object TrackLanguages {
    const val ORIGINAL = "original"

    fun displayName(code: String, locale: Locale): String {
        if (code == ORIGINAL) return "Original"
        if (code.isBlank()) return ""
        return Locale.forLanguageTag(code).getDisplayName(locale)
    }

    fun sortedFor(locale: Locale): List<String> {
        return listOf(
            "eng", "tur", "ger", "fre", "spa", "ita", "rus", "ara", "por", "zho", "jpn", "kor"
        )
    }
}
