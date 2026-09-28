package tv.own.owntv.core.settings

import tv.own.owntv.core.database.entity.SourceEntity

object SourceOverrides {
    fun headersWithReferer(httpHeaders: String?, source: SourceEntity?): String? {
        val referer = source?.httpReferer?.takeIf { it.isNotBlank() } ?: return httpHeaders
        if (httpHeaders.isNullOrBlank()) {
            return "Referer: $referer"
        }
        if (httpHeaders.contains("Referer:", ignoreCase = true)) {
            return httpHeaders
        }
        return "$httpHeaders\nReferer: $referer"
    }

    fun vodEngineOf(source: SourceEntity?): String? = source?.vodEnginePreference
}
