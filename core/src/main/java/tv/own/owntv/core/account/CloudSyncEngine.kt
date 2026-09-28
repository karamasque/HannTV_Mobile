package tv.own.owntv.core.account

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.setup.SourceImporter

private const val TAG = "HanTVCloudSync"
private const val FIREBASE_API_KEY = "AIzaSyCitUUP3TuBwQjZBIjn4fUQX171WFt-2vo"
private const val FIREBASE_PROJECT_ID = "hantv-cloud"

class CloudSyncEngine(
    private val context: android.content.Context,
    private val http: HttpClient,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val progressDao: ProgressDao,
    private val sourceImporter: SourceImporter,
    private val accountManager: CloudAccountManager,
    private val movieDao: MovieDao? = null,
    private val seriesDao: SeriesDao? = null,
    private val epgMigration: tv.own.owntv.core.epg.EpgMigration? = null,
) {
    private val prefs = context.getSharedPreferences("hantv_cloud_sync_urls", android.content.Context.MODE_PRIVATE)

    private fun getSyncedUrls(): Set<String> {
        return prefs.getStringSet("synced_urls", emptySet()) ?: emptySet()
    }

    private fun saveSyncedUrl(url: String) {
        if (url.isBlank()) return
        val current = getSyncedUrls().toMutableSet()
        current.add(url)
        prefs.edit().putStringSet("synced_urls", current).apply()
    }

    private fun removeSyncedUrl(url: String) {
        if (url.isBlank()) return
        val current = getSyncedUrls().toMutableSet()
        current.remove(url)
        prefs.edit().putStringSet("synced_urls", current).apply()
    }

    /**
     * Perform full bidirectional synchronization of IPTV sources and playback progress.
     */
    suspend fun syncAll(): Boolean = withContext(Dispatchers.IO) {
        val uid = settings.cloudUserId.first().takeIf { it.isNotBlank() } ?: return@withContext false
        val token = accountManager.getValidToken().takeIf { it.isNotBlank() } ?: return@withContext false

        val user = accountManager.currentUser.value ?: accountManager.restoreSession()
        val email = settings.cloudUserEmail.first().ifBlank { user?.email ?: "" }
        val isAdmin = email.trim().lowercase() in listOf("admin@hantv.com", "kilicemre3437@gmail.com")
        val isPremium = user?.isPremium == true || isAdmin || user?.plan?.lowercase() == "premium"

        try {
            if (isPremium) {
                syncSources(uid, token)
                syncProgress(uid, token)
            } else {
                Log.i(TAG, "Cloud sync disabled for free tier user ($uid) - local only")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync error for uid=$uid", e)
            false
        }
    }

    suspend fun deleteSourceFromCloud(source: tv.own.owntv.core.database.entity.SourceEntity) = withContext(Dispatchers.IO) {
        removeSyncedUrl(source.url)
        val uid = settings.cloudUserId.first().takeIf { it.isNotBlank() } ?: return@withContext
        val token = accountManager.getValidToken().takeIf { it.isNotBlank() } ?: return@withContext

        try {
            val directDocUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid/sources/src_${source.id}?key=$FIREBASE_API_KEY"
            http.delete(directDocUrl, mapOf("Authorization" to "Bearer $token"))

            val url = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid/sources?key=$FIREBASE_API_KEY"
            val respStr = runCatching { http.getText(url, headers = mapOf("Authorization" to "Bearer $token")) }.getOrNull()
            if (respStr != null && respStr.contains("\"documents\"")) {
                val root = JSONObject(respStr)
                if (root.has("documents")) {
                    val docs = root.getJSONArray("documents")
                    for (i in 0 until docs.length()) {
                        val docObj = docs.getJSONObject(i)
                        val docName = docObj.optString("name", "")
                        val fields = docObj.optJSONObject("fields") ?: continue
                        val srcUrl = fields.optJSONObject("url")?.optString("stringValue", "") ?: ""
                        val username = fields.optJSONObject("username")?.optString("stringValue", "") ?: ""
                        
                        if (srcUrl == source.url || (username.isNotBlank() && username == source.username && srcUrl == source.url)) {
                            val subPath = docName.substringAfter("documents/")
                            val delUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/$subPath?key=$FIREBASE_API_KEY"
                            http.delete(delUrl, mapOf("Authorization" to "Bearer $token"))
                            Log.i(TAG, "Deleted cloud source document: $subPath")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting source from cloud: ${source.name}", e)
        }
    }

    private fun cleanUrl(url: String): String = url.trim().lowercase().removeSuffix("/")

    private suspend fun syncSources(uid: String, token: String) = withContext(Dispatchers.IO) {
        val url = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid/sources?key=$FIREBASE_API_KEY"
        val respStr = runCatching { http.getText(url, headers = mapOf("Authorization" to "Bearer $token")) }.getOrNull()
        
        val localSources = sourceDao.getAllOnce()
        val cloudSourceUrls = mutableSetOf<String>()
        val previouslySynced = getSyncedUrls()
        var fetchSuccess = false

        if (respStr != null) {
            fetchSuccess = true
            if (respStr.contains("\"documents\"")) {
                val root = JSONObject(respStr)
                if (root.has("documents")) {
                    val docs = root.getJSONArray("documents")
                    for (i in 0 until docs.length()) {
                        val docObj = docs.getJSONObject(i)
                        val fields = docObj.optJSONObject("fields") ?: continue

                        val name = fields.optJSONObject("name")?.optString("stringValue", "") ?: ""
                        val typeStr = fields.optJSONObject("type")?.optString("stringValue", "XTREAM") ?: "XTREAM"
                        val sourceUrl = fields.optJSONObject("url")?.optString("stringValue", "") ?: ""
                        val username = fields.optJSONObject("username")?.optString("stringValue", "") ?: ""
                        val password = fields.optJSONObject("password")?.optString("stringValue", "") ?: ""
                        val epgUrl = fields.optJSONObject("epgUrl")?.optString("stringValue", "") ?: ""
                        val userAgent = fields.optJSONObject("userAgent")?.optString("stringValue", "") ?: ""
                        val mac = fields.optJSONObject("mac")?.optString("stringValue", "") ?: ""

                        if (sourceUrl.isBlank()) continue
                        cloudSourceUrls.add(sourceUrl)
                        saveSyncedUrl(sourceUrl)

                        val type = runCatching { SourceType.valueOf(typeStr) }.getOrDefault(SourceType.XTREAM)
                        val cleanSrcUrl = cleanUrl(sourceUrl)
                        val exists = localSources.any { cleanUrl(it.url) == cleanSrcUrl || (it.username == username && cleanUrl(it.url) == cleanSrcUrl) }

                        if (!exists) {
                            Log.i(TAG, "Importing cloud source: $name ($typeStr)")
                            runCatching {
                                val activePid = settings.activeProfileId.first()
                                if (activePid > 0) {
                                    sourceImporter.useProfile(activePid)
                                }
                                when (type) {
                                    SourceType.XTREAM -> sourceImporter.xtream(name = name, server = sourceUrl, username = username, password = password, userAgent = userAgent, epgUrl = epgUrl)
                                    SourceType.M3U -> sourceImporter.m3u(name = name, url = sourceUrl, userAgent = userAgent, epgUrl = epgUrl)
                                    SourceType.STALKER -> sourceImporter.stalker(name = name, portalUrl = sourceUrl, mac = username.ifBlank { mac }, userAgent = userAgent)
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }

        if (fetchSuccess) {
            val cloudCleanSet = cloudSourceUrls.map { cleanUrl(it) }.toSet()
            val prevCleanSet = previouslySynced.map { cleanUrl(it) }.toSet()

            // Delete local sources that were previously synced but are now missing from Cloud (remotely deleted)
            for (local in localSources) {
                if (local.url.isNotBlank()) {
                    val localClean = cleanUrl(local.url)
                    if (!cloudCleanSet.contains(localClean) && prevCleanSet.contains(localClean)) {
                        Log.i(TAG, "Deleting local source missing from Cloud: ${local.name}")
                        runCatching { sourceDao.delete(local) }
                        removeSyncedUrl(local.url)
                    }
                }
            }

            // Push fresh local sources to Cloud (newly added locally)
            val updatedLocal = sourceDao.getAllOnce()
            for (local in updatedLocal) {
                if (local.url.isNotBlank()) {
                    val localClean = cleanUrl(local.url)
                    if (!cloudCleanSet.contains(localClean) && !prevCleanSet.contains(localClean)) {
                        Log.i(TAG, "Pushing new local source to Cloud: ${local.name}")
                        val sourceId = "src_${local.id}"
                        val patchUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid/sources/$sourceId?key=$FIREBASE_API_KEY"
                        val patchBody = JSONObject().apply {
                            put("fields", JSONObject().apply {
                                put("id", JSONObject().put("stringValue", sourceId))
                                put("name", JSONObject().put("stringValue", local.name))
                                put("type", JSONObject().put("stringValue", local.type.name))
                                put("url", JSONObject().put("stringValue", local.url ?: ""))
                                put("username", JSONObject().put("stringValue", local.username ?: ""))
                                put("password", JSONObject().put("stringValue", local.password ?: ""))
                                put("epgUrl", JSONObject().put("stringValue", local.epgUrl ?: ""))
                                put("userAgent", JSONObject().put("stringValue", local.userAgent ?: ""))
                                put("mac", JSONObject().put("stringValue", local.mac ?: ""))
                                put("updatedAt", JSONObject().put("stringValue", System.currentTimeMillis().toString()))
                            })
                        }.toString()
                        val res = runCatching { http.patchJson(patchUrl, patchBody, mapOf("Authorization" to "Bearer $token")) }
                        if (res.isSuccess) {
                            saveSyncedUrl(local.url)
                        }
                    }
                }
            }

            runCatching { epgMigration?.ensureEpgSourcesForPlaylists() }
        }
    }

    private suspend fun syncProgress(uid: String, token: String) = withContext(Dispatchers.IO) {
        // 1. Push local progress records to Cloud
        val localProgressList = progressDao.getAllOnce()
        for (prog in localProgressList) {
            val mediaKey = "key_${prog.mediaType}_${prog.itemId}".replace(Regex("[^a-zA-Z0-9_]"), "_")
            val patchUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid/progress/$mediaKey?key=$FIREBASE_API_KEY"
            
            var title = "İçerik"
            if (prog.mediaType == MediaType.MOVIE) {
                movieDao?.getById(prog.itemId)?.let { title = it.name }
            } else if (prog.mediaType == MediaType.EPISODE) {
                val ep = seriesDao?.getEpisodeById(prog.itemId)
                val series = ep?.seriesId?.let { seriesDao.getSeriesById(it) }
                if (series != null && ep != null) {
                    title = "${series.name} (S${ep.seasonNumber}E${ep.episodeNumber})"
                } else if (ep != null) {
                    title = ep.name
                }
            }

            val patchBody = JSONObject().apply {
                put("fields", JSONObject().apply {
                    put("mediaKey", JSONObject().put("stringValue", mediaKey))
                    put("mediaType", JSONObject().put("stringValue", prog.mediaType.name))
                    put("itemId", JSONObject().put("integerValue", prog.itemId.toString()))
                    put("profileId", JSONObject().put("integerValue", prog.profileId.toString()))
                    put("positionMs", JSONObject().put("integerValue", prog.positionMs.toString()))
                    put("durationMs", JSONObject().put("integerValue", prog.durationMs.toString()))
                    put("updatedAt", JSONObject().put("stringValue", prog.updatedAt.toString()))
                    put("title", JSONObject().put("stringValue", title))
                })
            }.toString()

            runCatching { http.patchJson(patchUrl, patchBody, mapOf("Authorization" to "Bearer $token")) }
        }

        // 2. Pull remote progress records from Cloud
        val url = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid/progress?key=$FIREBASE_API_KEY"
        val respStr = runCatching { http.getText(url, headers = mapOf("Authorization" to "Bearer $token")) }.getOrNull() ?: return@withContext
        val root = JSONObject(respStr)

        if (!root.has("documents")) return@withContext
        val docs = root.getJSONArray("documents")

        for (i in 0 until docs.length()) {
            val docObj = docs.getJSONObject(i)
            val fields = docObj.optJSONObject("fields") ?: continue

            val typeStr = fields.optJSONObject("mediaType")?.optString("stringValue", "MOVIE") ?: "MOVIE"
            val mediaType = runCatching { MediaType.valueOf(typeStr) }.getOrDefault(MediaType.MOVIE)
            val itemId = fields.optJSONObject("itemId")?.optString("integerValue", "0")?.toLongOrNull() 
                ?: fields.optJSONObject("itemId")?.optLong("integerValue", 0L) 
                ?: 0L
            val profileId = fields.optJSONObject("profileId")?.optString("integerValue", "1")?.toLongOrNull() 
                ?: fields.optJSONObject("profileId")?.optLong("integerValue", 1L) 
                ?: 1L
            val positionMs = fields.optJSONObject("positionMs")?.optString("integerValue", "0")?.toLongOrNull() 
                ?: fields.optJSONObject("positionMs")?.optLong("integerValue", 0L) 
                ?: 0L
            val durationMs = fields.optJSONObject("durationMs")?.optString("integerValue", "1")?.toLongOrNull() 
                ?: fields.optJSONObject("durationMs")?.optLong("integerValue", 1L) 
                ?: 1L
            val updatedAt = fields.optJSONObject("updatedAt")?.optString("stringValue", "0")?.toLongOrNull() 
                ?: System.currentTimeMillis()

            if (positionMs > 0 && itemId > 0) {
                progressDao.insertIfAbsent(
                    PlaybackProgressEntity(
                        profileId = profileId,
                        mediaType = mediaType,
                        itemId = itemId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = updatedAt,
                    )
                )
                progressDao.updateIfNewer(
                    profileId = profileId,
                    type = mediaType,
                    itemId = itemId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    at = updatedAt,
                )
            }
        }
    }
}

