package tv.own.owntv.core.setup

/**
 * Result of smart analyzing an input string (M3U URL, Xtream URL, or multiline text).
 */
data class SmartAnalysisResult(
    /** True if Xtream Codes server, username, and password were successfully extracted. */
    val isXtream: Boolean,
    /** Server base URL (e.g. "http://eumaxim.shop:8080"). */
    val serverUrl: String,
    /** Username for authentication. */
    val username: String,
    /** Password for authentication. */
    val password: String,
    /** Output format if specified in the URL (e.g. "ts", "m3u8", "m3u_plus"). */
    val outputFormat: String? = null,
    /** Full M3U URL (cleaned/normalized). */
    val m3uUrl: String? = null,
    /** Suggested playlist name derived from host or username. */
    val suggestedName: String? = null,
    /** Original raw input. */
    val rawInput: String = "",
)

/**
 * Intelligent analyzer that automatically extracts Xtream Codes server credentials
 * (Server URL, Username, Password) from various M3U URLs, get.php links, path formats,
 * or raw copied account text.
 */
object SmartSourceAnalyzer {

    /**
     * Analyzes any input string (M3U link, get.php URL, stream URL, or multiline text)
     * and extracts structured credentials.
     */
    fun analyze(input: String?): SmartAnalysisResult? {
        if (input.isNullOrBlank()) return null
        val text = input.trim()

        // 1. Try multiline text parsing (e.g. pasted from WhatsApp, Telegram, Email)
        if (text.contains("\n") || text.contains("\r")) {
            val multiline = parseMultilineText(text)
            if (multiline != null) return multiline
        }

        // 2. Try URL query parameters (get.php?username=...&password=... etc.)
        val queryResult = parseQueryParamUrl(text)
        if (queryResult != null) return queryResult

        // 3. Try path-based Xtream URLs (http://host:8080/live/user/pass/id.ts or /movie/... or /series/... or /playlist/...)
        val pathResult = parsePathBasedUrl(text)
        if (pathResult != null) return pathResult

        // 4. Try embedded userinfo (http://user:pass@host:port)
        val userinfoResult = parseUserInfoUrl(text)
        if (userinfoResult != null) return userinfoResult

        // 5. Fallback: Generic M3U link
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true) ||
            text.startsWith("file://", ignoreCase = true) ||
            text.startsWith("content://", ignoreCase = true) ||
            text.startsWith("/")
        ) {
            val host = extractHost(text)
            return SmartAnalysisResult(
                isXtream = false,
                serverUrl = text,
                username = "",
                password = "",
                m3uUrl = text,
                suggestedName = host ?: "M3U Playlist",
                rawInput = text,
            )
        }

        return null
    }

    /**
     * Parses URLs with query parameters like:
     * - http://eumaxim.shop:8080/get.php?username=1905mehmetjti&password=31122025memo&type=m3u
     * - http://eumaxim.shop:8080/get.php?username=12361huso061061&password=123061057huso61&output=ts
     * - http://host:8080/player_api.php?username=user&password=pass
     * - http://host:8080/xmltv.php?username=user&password=pass
     */
    private fun parseQueryParamUrl(url: String): SmartAnalysisResult? {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            return null
        }

        val questionIdx = cleanUrl.indexOf('?')
        if (questionIdx < 0) return null

        val queryPart = cleanUrl.substring(questionIdx + 1)
        val params = HashMap<String, String>()
        for (pair in queryPart.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val key = pair.substring(0, eq).trim().lowercase()
                val value = pair.substring(eq + 1).trim()
                params[key] = value
            }
        }

        val user = params["username"] ?: params["user"] ?: params["usr"] ?: params["u"]
        val pass = params["password"] ?: params["pass"] ?: params["pwd"] ?: params["p"]
        val output = params["output"] ?: params["type"] ?: params["format"]

        if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
            val server = extractBaseServer(cleanUrl)
            val host = extractHost(cleanUrl)
            val suggested = host?.substringBefore('.')?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it.isNotBlank() } ?: user

            return SmartAnalysisResult(
                isXtream = true,
                serverUrl = server,
                username = user,
                password = pass,
                outputFormat = output,
                m3uUrl = cleanUrl,
                suggestedName = suggested,
                rawInput = url,
            )
        }

        return null
    }

    /**
     * Parses path-based Xtream URLs:
     * - http://host:8080/live/username/password/12345.ts
     * - http://host:8080/movie/username/password/12345.mp4
     * - http://host:8080/series/username/password/12345.mp4
     * - http://host:8080/playlist/username/password/m3u_plus
     * - http://host:8080/timeshift/username/password/...
     */
    private fun parsePathBasedUrl(url: String): SmartAnalysisResult? {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            return null
        }

        val regex = Regex("""(?i)^(https?://[^/]+)/(?:live|movie|series|vod|playlist|timeshift|hls)/([^/]+)/([^/]+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(cleanUrl) ?: return null

        val server = match.groupValues[1]
        val user = match.groupValues[2]
        val pass = match.groupValues[3]

        if (user.isNotBlank() && pass.isNotBlank() && !user.contains("=") && !pass.contains("=")) {
            val host = extractHost(cleanUrl)
            val suggested = host?.substringBefore('.')?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it.isNotBlank() } ?: user

            return SmartAnalysisResult(
                isXtream = true,
                serverUrl = server,
                username = user,
                password = pass,
                outputFormat = "ts",
                m3uUrl = cleanUrl,
                suggestedName = suggested,
                rawInput = url,
            )
        }

        return null
    }

    /**
     * Parses URLs with embedded credentials:
     * - http://username:password@host:port/path
     */
    private fun parseUserInfoUrl(url: String): SmartAnalysisResult? {
        val cleanUrl = url.trim()
        val regex = Regex("""(?i)^(https?://)([^:@/]+):([^:@/]+)@([^/]+)(.*)""")
        val match = regex.find(cleanUrl) ?: return null

        val scheme = match.groupValues[1]
        val user = match.groupValues[2]
        val pass = match.groupValues[3]
        val hostAndPort = match.groupValues[4]

        if (user.isNotBlank() && pass.isNotBlank()) {
            val server = "$scheme$hostAndPort"
            return SmartAnalysisResult(
                isXtream = true,
                serverUrl = server,
                username = user,
                password = pass,
                m3uUrl = cleanUrl,
                suggestedName = user,
                rawInput = url,
            )
        }

        return null
    }

    /**
     * Parses multiline text formatted like:
     * Sunucu / Host / URL: http://eumaxim.shop:8080
     * Kullanıcı / User / Username: 1905mehmetjti
     * Şifre / Pass / Password: 31122025memo
     */
    private fun parseMultilineText(text: String): SmartAnalysisResult? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var server: String? = null
        var user: String? = null
        var pass: String? = null
        var m3uUrl: String? = null

        val serverKeys = listOf("url", "host", "server", "sunucu", "adres", "portal", "link", "dns")
        val userKeys = listOf("username", "user", "kullanici", "kullanıcı", "usr", "u", "hesap", "account")
        val passKeys = listOf("password", "pass", "sifre", "şifre", "parola", "pwd", "p")
        val m3uKeys = listOf("m3u", "m3u_plus", "playlist", "m3u8")

        for (line in lines) {
            val colonIdx = line.indexOf(':')
            val eqIdx = line.indexOf('=')
            val sepIdx = if (colonIdx > 0 && (eqIdx == -1 || colonIdx < eqIdx)) colonIdx else eqIdx

            if (sepIdx > 0) {
                val key = line.substring(0, sepIdx).trim().lowercase()
                val value = line.substring(sepIdx + 1).trim()

                if (value.isNotBlank()) {
                    if (serverKeys.any { key.contains(it) } && (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true))) {
                        server = extractBaseServer(value)
                    } else if (userKeys.any { key.contains(it) }) {
                        user = value
                    } else if (passKeys.any { key.contains(it) }) {
                        pass = value
                    } else if (m3uKeys.any { key.contains(it) }) {
                        m3uUrl = value
                    }
                }
            } else if (line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true)) {
                val analyzed = parseQueryParamUrl(line) ?: parsePathBasedUrl(line)
                if (analyzed != null) return analyzed
                if (server == null) server = extractBaseServer(line)
            }
        }

        if (!server.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank()) {
            return SmartAnalysisResult(
                isXtream = true,
                serverUrl = server,
                username = user,
                password = pass,
                m3uUrl = m3uUrl ?: "$server/get.php?username=$user&password=$pass&type=m3u_plus&output=ts",
                suggestedName = user,
                rawInput = text,
            )
        }

        return null
    }

    private fun extractBaseServer(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return url
        val hostStart = schemeEnd + 3
        val pathStart = url.indexOf('/', hostStart)
        return if (pathStart > 0) url.substring(0, pathStart) else url
    }

    private fun extractHost(url: String): String? {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd > 0) schemeEnd + 3 else 0
        val end = url.indexOfAny(charArrayOf('/', ':', '?', '#'), start)
        val host = if (end > start) url.substring(start, end) else if (start < url.length) url.substring(start) else null
        return host?.takeIf { it.isNotBlank() }
    }
}
