package tv.own.owntv.core.scraper

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ForumIPTVAccount(
    val host: String,
    val username: String,
    val password: String,
    var status: String = "Bilinmiyor",
    var expiry: String = "Bilinmiyor",
)

data class ForumScrapeResult(
    val success: Boolean,
    val accounts: List<ForumIPTVAccount> = emptyList(),
    val message: String? = null,
    val totalCandidates: Int = 0,
)

class MemoryCookieJar : CookieJar {
    private val cookieStore = CopyOnWriteArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { newCookie ->
            cookieStore.removeAll { it.name == newCookie.name }
            cookieStore.add(newCookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookieStore.removeAll { it.expiresAt < now }
        return cookieStore.filter { it.matches(url) }
    }
}

object ForumScraper {

    private fun buildSslSetup(): Pair<SSLContext?, TrustManager> {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            },
        )
        val sslCtx = runCatching {
            SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        }.getOrNull() ?: runCatching {
            SSLContext.getInstance("SSL").apply { init(null, trustAllCerts, SecureRandom()) }
        }.getOrNull()
        return Pair(sslCtx, trustAllCerts[0])
    }

    private fun getUnsafeOkHttpClient(cookieJar: CookieJar): OkHttpClient {
        val (sslCtx, tm) = buildSslSetup()
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8")
                        .build(),
                )
            }
            .cookieJar(cookieJar)
        if (sslCtx != null) {
            builder.sslSocketFactory(sslCtx.socketFactory, tm as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private fun getValidationOkHttpClient(): OkHttpClient {
        val (sslCtx, tm) = buildSslSetup()
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 200
            maxRequestsPerHost = 20
        }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Accept", "*/*")
                        .build(),
                )
            }
        if (sslCtx != null) {
            builder.sslSocketFactory(sslCtx.socketFactory, tm as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private fun unescapeHtml(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun safeUrlDecode(value: String): String {
        return try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            value
        }
    }

    private fun normalizeHost(rawHost: String): String {
        var trimmed = rawHost.trim().removeSuffix("/")
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "http://$trimmed"
        }
        return trimmed
    }

    fun parseXtreamUrl(url: String): ForumIPTVAccount? {
        return try {
            val unescaped = unescapeHtml(url.trim())
            if (unescaped.length < 10) return null
            val urlLower = unescaped.lowercase(Locale.ROOT)

            // 1. Live/Movie/Series/Play path format (e.g. http://host:port/live/user/pass/123.ts)
            for (kw in listOf("/live/", "/movie/", "/series/", "/play/")) {
                if (urlLower.contains(kw)) {
                    val idx = urlLower.indexOf(kw)
                    if (idx <= 0) continue
                    val rawHost = unescaped.substring(0, idx).trim().removeSuffix("/")
                    val host = normalizeHost(rawHost)
                    val rest = unescaped.substring(idx + kw.length)
                    val parts = rest.split("/").filter { it.isNotEmpty() }
                    if (parts.size >= 2) {
                        val username = parts[0].trim()
                        var password = parts[1].trim()
                        if (password.contains("?")) password = password.substringBefore("?")
                        if (password.contains(".")) password = password.substringBefore(".")
                        if (username.isNotEmpty() && password.isNotEmpty()) {
                            return ForumIPTVAccount(
                                host = host,
                                username = safeUrlDecode(username),
                                password = safeUrlDecode(password),
                            )
                        }
                    }
                }
            }

            // 2. Query parameters format (get.php, player_api.php, xmltv.php)
            for (kw in listOf("/get.php", "/player_api.php", "/xmltv.php")) {
                if (urlLower.contains(kw)) {
                    val idx = urlLower.indexOf(kw)
                    if (idx <= 0) continue
                    val rawHost = unescaped.substring(0, idx).trim().removeSuffix("/")
                    val host = normalizeHost(rawHost)
                    val userMatch = Regex("""[?&](?:username|user|auth)=([^&#\s]+)""", RegexOption.IGNORE_CASE).find(unescaped)
                    val passMatch = Regex("""[?&](?:password|pass)=([^&#\s]+)""", RegexOption.IGNORE_CASE).find(unescaped)
                    if (userMatch != null && passMatch != null) {
                        val u = safeUrlDecode(userMatch.groupValues[1].trim())
                        val p = safeUrlDecode(passMatch.groupValues[1].trim())
                        if (u.isNotEmpty() && p.isNotEmpty()) {
                            return ForumIPTVAccount(
                                host = host,
                                username = u,
                                password = p,
                            )
                        }
                    }
                }
            }

            // 3. Plain text line format: http://host:port user pass
            val lineMatch = Regex("""(https?://[^\s/:,]+:\d+)[/\s,;|]+([^\s:,;|]+)[\s:,;|]+([^\s:,;|]+)""").find(unescaped)
            if (lineMatch != null) {
                val host = normalizeHost(lineMatch.groupValues[1])
                val u = safeUrlDecode(lineMatch.groupValues[2].trim())
                val p = safeUrlDecode(lineMatch.groupValues[3].trim())
                if (u.isNotEmpty() && p.isNotEmpty()) {
                    return ForumIPTVAccount(
                        host = host,
                        username = u,
                        password = p,
                    )
                }
            }

            null
        } catch (_: Throwable) {
            null
        }
    }

    fun parseText(text: String): List<ForumIPTVAccount> {
        val list = ArrayList<ForumIPTVAccount>()
        val seen = HashSet<String>()
        val textUnescaped = unescapeHtml(text)

        // 1. Scan URLs
        val urlPattern = runCatching { Pattern.compile("https?://[^\\s\"'<>]+") }.getOrNull()
        if (urlPattern != null) {
            val matcher = urlPattern.matcher(textUnescaped)
            while (matcher.find()) {
                val url = matcher.group(0) ?: continue
                val parsed = parseXtreamUrl(url)
                if (parsed != null && parsed.host.isNotBlank() && parsed.username.isNotBlank() && parsed.password.isNotBlank()) {
                    val key = "${parsed.host}_${parsed.username}_${parsed.password}"
                    if (seen.add(key)) {
                        list.add(parsed)
                    }
                }
            }
        }

        // 2. Scan text lines
        val lines = textUnescaped.split('\n', '\r')
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length < 10) continue
            val parsed = parseXtreamUrl(trimmed)
            if (parsed != null && parsed.host.isNotBlank() && parsed.username.isNotBlank() && parsed.password.isNotBlank()) {
                val key = "${parsed.host}_${parsed.username}_${parsed.password}"
                if (seen.add(key)) {
                    list.add(parsed)
                }
            }
        }
        return list
    }

    private const val MASK_KEY = 0x5A
    // "mR.Gentleman" XOR 0x5A
    private val OBF_USER = byteArrayOf(0x37, 0x08, 0x74, 0x1D, 0x3F, 0x34, 0x2E, 0x36, 0x3F, 0x37, 0x3B, 0x34)
    // "Th3nexus92" XOR 0x5A -> 'T'=0x54 ^ 0x5A = 0x0E
    private val OBF_PASS = byteArrayOf(0x0E, 0x32, 0x69, 0x34, 0x3F, 0x22, 0x2F, 0x29, 0x63, 0x68)

    fun getInternalUser(): String =
        String(OBF_USER.map { (it.toInt() xor MASK_KEY).toByte() }.toByteArray(), Charsets.UTF_8)

    fun getInternalPass(): String =
        String(OBF_PASS.map { (it.toInt() xor MASK_KEY).toByte() }.toByteArray(), Charsets.UTF_8)

    private val TARGET_THREADS = listOf(
        "https://forumsitesi.com.tr/konular/byxm-premium-uyelere-ozel-iptv-m3u-panel-linkleri-2026.1285769/",
        "https://forumsitesi.com.tr/konular/byxm-herkese-acik-iptv-m3u-panel-linkleri-2026.1285748/",
    )

    suspend fun scrapeAndValidate(
        user: String = "",
        pass: String = "",
        progressCallback: (Float, String) -> Unit,
    ): ForumScrapeResult = withContext(Dispatchers.IO) {
        val effectiveUser = user.takeIf { it.isNotBlank() } ?: getInternalUser()
        val effectivePass = pass.takeIf { it.isNotBlank() } ?: getInternalPass()

        try {
            progressCallback(0.05f, "Sunucu bağlantısı kuruluyor...")
            val cookieJar = MemoryCookieJar()
            val client = getUnsafeOkHttpClient(cookieJar)

            // Step 1: Attempt login if possible
            progressCallback(0.10f, "Otomatik oturum açılıyor...")
            val rLogin = Request.Builder().url("https://forumsitesi.com.tr/login/").build()
            var token = ""
            runCatching {
                client.newCall(rLogin).execute().use { response ->
                    if (response.isSuccessful || response.code in 200..399) {
                        val body = response.body.string()
                        val regex1 = Regex("""name="_xfToken"\s*value="([^"]+)"""")
                        val regex2 = Regex("""data-csrf="([^"]+)"""")
                        val match = regex1.find(body) ?: regex2.find(body)
                        if (match != null && match.groupValues.size > 1) {
                            token = match.groupValues[1]
                        }
                    }
                }
            }

            if (token.isNotBlank()) {
                val formBody = FormBody.Builder()
                    .add("login", effectiveUser.trim())
                    .add("password", effectivePass.trim())
                    .add("_xfToken", token)
                    .add("remember", "1")
                    .add("_xfRedirect", "https://forumsitesi.com.tr/")
                    .add("_xfResponseType", "json")
                    .build()

                val rPost = Request.Builder()
                    .url("https://forumsitesi.com.tr/login/login")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Referer", "https://forumsitesi.com.tr/login/")
                    .header("Origin", "https://forumsitesi.com.tr")
                    .post(formBody)
                    .build()

                runCatching {
                    client.newCall(rPost).execute().use { response ->
                        response.body.string()
                    }
                }
            }

            // Step 2: Fetch newest thread pages and extract IPTV accounts
            progressCallback(0.20f, "IPTV Adresleri taranıyor...")

            val allPageUrls = mutableListOf<String>()
            supervisorScope {
                val threadJobs = TARGET_THREADS.map { threadUrl ->
                    async {
                        val rThread = Request.Builder().url(threadUrl).build()
                        var lastPage = 1
                        runCatching {
                            client.newCall(rThread).execute().use { response ->
                                val body = response.body.string()
                                val pageRegex = Regex("""page-(\d+)""")
                                val pageMatches = pageRegex.findAll(body).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
                                if (pageMatches.isNotEmpty()) {
                                    lastPage = pageMatches.maxOrNull() ?: 1
                                }
                            }
                        }
                        val urls = mutableListOf<String>()
                        if (lastPage > 1) {
                            urls.add("${threadUrl}page-$lastPage")
                            if (lastPage > 2) urls.add("${threadUrl}page-${lastPage - 1}")
                            if (lastPage > 3) urls.add("${threadUrl}page-${lastPage - 2}")
                        } else {
                            urls.add(threadUrl)
                        }
                        urls
                    }
                }
                threadJobs.awaitAll().forEach { allPageUrls.addAll(it) }
            }

            progressCallback(0.30f, "En güncel paylaşımlar toplanıyor...")

            val filteredAccs = ArrayList<ForumIPTVAccount>()
            val seen = HashSet<String>()
            val MAX_CANDIDATES = 400

            for (pageUrl in allPageUrls) {
                if (filteredAccs.size >= MAX_CANDIDATES) break
                val request = runCatching { Request.Builder().url(pageUrl).build() }.getOrNull() ?: continue
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val pageHtml = response.body.string()
                            val postMatches = Regex("""<article\s+class="message-body[^"]*">(.*?)</article>""", setOf(RegexOption.DOT_MATCHES_ALL))
                                .findAll(pageHtml).map { it.groupValues[1] }.toList()
                            val postsInReverse = if (postMatches.isNotEmpty()) postMatches.reversed() else listOf(pageHtml)

                            for (postContent in postsInReverse) {
                                val hrefs = Regex("""href="([^"]+)"""").findAll(postContent).mapNotNull { it.groupValues.getOrNull(1) }.joinToString("\n")
                                val pageText = postContent + "\n" + hrefs
                                val pageAccounts = parseText(pageText)
                                for (acc in pageAccounts) {
                                    val hostLower = acc.host.lowercase(Locale.ROOT)
                                    if (hostLower.contains("forumsitesi.com.tr") || hostLower.contains("uyduportal.com") ||
                                        hostLower.contains("google") || hostLower.contains("yandex") ||
                                        hostLower.contains("github") || hostLower.contains("facebook") ||
                                        hostLower.contains("t.me") || hostLower.contains("telegram")) {
                                        continue
                                    }
                                    val key = "${acc.host}_${acc.username}_${acc.password}"
                                    if (seen.add(key)) {
                                        filteredAccs.add(acc)
                                        if (filteredAccs.size >= MAX_CANDIDATES) break
                                    }
                                }
                                if (filteredAccs.size >= MAX_CANDIDATES) break
                            }
                        }
                    }
                }
            }

            if (filteredAccs.isEmpty()) {
                return@withContext ForumScrapeResult(
                    success = false,
                    message = "Şu anda taranan başlıklarda IPTV bulunamadı.",
                )
            }

            val totalCandidatesCount = filteredAccs.size
            progressCallback(0.45f, "$totalCandidatesCount aday bulundu, doğrulanıyor...")

            val validationClient = getValidationOkHttpClient()

            fun validateAccount(acc: ForumIPTVAccount): ForumIPTVAccount? {
                val httpUrl = acc.host.toHttpUrlOrNull() ?: return null
                val url = runCatching {
                    httpUrl.newBuilder()
                        .encodedPath("/player_api.php")
                        .setQueryParameter("username", acc.username)
                        .setQueryParameter("password", acc.password)
                        .build()
                }.getOrNull() ?: return null

                val req = Request.Builder().url(url).build()
                return runCatching {
                    validationClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body.string()
                            if (body.contains("user_info") || body.contains("username") || body.contains("status")) {
                                val json = runCatching { JSONObject(body) }.getOrNull()
                                val userInfo = json?.optJSONObject("user_info")
                                val status = userInfo?.optString("status", "").orEmpty()
                                val auth = userInfo?.optInt("auth", 1) ?: 1
                                val isActive = auth != 0 && !status.equals("disabled", true) && !status.equals("banned", true)
                                if (isActive) {
                                    acc.status = "Aktif"
                                    val exp = userInfo?.optString("exp_date", "").orEmpty()
                                    acc.expiry = if (exp.isBlank() || exp == "null" || exp == "0") {
                                        "Limitsiz"
                                    } else {
                                        val ts = exp.toLongOrNull()
                                        if (ts != null && ts > 0) {
                                            runCatching {
                                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts * 1000L))
                                            }.getOrDefault(exp)
                                        } else exp
                                    }
                                    acc
                                } else null
                            } else null
                        } else null
                    }
                }.getOrNull()
            }

            val BATCH_SIZE = 35
            val allCandidates = filteredAccs.take(250)
            val totalBatches = (allCandidates.size + BATCH_SIZE - 1) / BATCH_SIZE
            val validatedAccs = mutableListOf<ForumIPTVAccount>()

            for ((batchIdx, batch) in allCandidates.chunked(BATCH_SIZE).withIndex()) {
                val batchProgress = 0.45f + (batchIdx.toFloat() / totalBatches) * 0.50f
                progressCallback(batchProgress, "Doğrulanıyor: ${validatedAccs.size} aktif / ${minOf((batchIdx + 1) * BATCH_SIZE, allCandidates.size)} kontrol edildi")
                val batchResults = supervisorScope {
                    batch.map { acc -> async { validateAccount(acc) } }.awaitAll()
                }
                validatedAccs.addAll(batchResults.filterNotNull())
            }

            if (validatedAccs.isNotEmpty()) {
                progressCallback(1.0f, "${validatedAccs.size} aktif IPTV hazır!")
                return@withContext ForumScrapeResult(
                    success = true,
                    accounts = validatedAccs,
                    totalCandidates = totalCandidatesCount,
                )
            }

            return@withContext ForumScrapeResult(
                success = false,
                message = "Taranan hesapların süresi dolmuş veya erişilemez durumda.",
                totalCandidates = totalCandidatesCount,
            )
        } catch (e: Throwable) {
            return@withContext ForumScrapeResult(
                success = false,
                message = "Kaynak taraması başarısız oldu: ${e.localizedMessage ?: "Bağlantı hatası"}",
            )
        }
    }
}
