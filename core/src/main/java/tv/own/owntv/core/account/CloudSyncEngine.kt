package tv.own.owntv.core.account

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.setup.SourceImporter

private const val TAG = "HanTVCloudSync"
private const val FIREBASE_API_KEY = "AIzaSyD-HanTVCloudSyncSecretKey2026"
private const val FIREBASE_PROJECT_ID = "hantv-cloud"

class CloudSyncEngine(
    private val http: HttpClient,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val progressDao: ProgressDao,
    private val sourceImporter: SourceImporter,
    private val accountManager: CloudAccountManager,
) {
    /**
     * Perform full bidirectional synchronization of IPTV sources and playback progress.
     */
    suspend fun syncAll(): Boolean = withContext(Dispatchers.IO) {
        val uid = settings.cloudUserId.first().takeIf { it.isNotBlank() } ?: return@withContext false
        val token = settings.cloudIdToken.first().takeIf { it.isNotBlank() } ?: return@withContext false

        try {
            syncSources(uid, token)
            syncProgress(uid, token)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync error for uid=$uid", e)
            false
        }
    }

    private suspend fun syncSources(uid: String, token: String) = withContext(Dispatchers.IO) {
        val url = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid/sources?key=$FIREBASE_API_KEY"
        val respStr = runCatching { http.getText(url, headers = mapOf("Authorization" to "Bearer $token")) }.getOrNull() ?: return@withContext
        val root = JSONObject(respStr)

        if (!root.has("documents")) return@withContext
        val docs = root.getJSONArray("documents")

        val localSources = sourceDao.getAllOnce()

        for (i in 0 until docs.length()) {
            val docObj = docs.getJSONObject(i)
            val fields = docObj.optJSONObject("fields") ?: continue

            val name = fields.optJSONObject("name")?.optString("stringValue", "") ?: ""
            val typeStr = fields.optJSONObject("type")?.optString("stringValue", "XTREAM") ?: "XTREAM"
            val sourceUrl = fields.optJSONObject("url")?.optString("stringValue", "") ?: ""
            val username = fields.optJSONObject("username")?.optString("stringValue", "") ?: ""
            val password = fields.optJSONObject("password")?.optString("stringValue", "") ?: ""

            if (sourceUrl.isBlank()) continue

            val type = runCatching { SourceType.valueOf(typeStr) }.getOrDefault(SourceType.XTREAM)
            val exists = localSources.any { it.url == sourceUrl || (it.username == username && it.url == sourceUrl) }

            if (!exists) {
                Log.i(TAG, "Importing cloud source: $name ($typeStr)")
                runCatching {
                    when (type) {
                        SourceType.XTREAM -> sourceImporter.xtream(name = name, server = sourceUrl, username = username, password = password)
                        SourceType.M3U -> sourceImporter.m3u(name = name, url = sourceUrl)
                        SourceType.STALKER -> sourceImporter.stalker(name = name, portalUrl = sourceUrl, mac = username)
                        else -> {}
                    }
                }
            }
        }
    }

    private suspend fun syncProgress(uid: String, token: String) = withContext(Dispatchers.IO) {
        // 1. Push local progress records to Cloud
        val localProgressList = progressDao.getAllOnce()
        for (prog in localProgressList) {
            val mediaKey = "key_${prog.mediaType}_${prog.itemId}".replace(Regex("[^a-zA-Z0-9_]"), "_")
            val patchUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid/progress/$mediaKey?key=$FIREBASE_API_KEY"
            val patchBody = JSONObject().apply {
                put("fields", JSONObject().apply {
                    put("mediaKey", JSONObject().put("stringValue", mediaKey))
                    put("mediaType", JSONObject().put("stringValue", prog.mediaType.name))
                    put("itemId", JSONObject().put("integerValue", prog.itemId))
                    put("profileId", JSONObject().put("integerValue", prog.profileId))
                    put("positionMs", JSONObject().put("integerValue", prog.positionMs))
                    put("durationMs", JSONObject().put("integerValue", prog.durationMs))
                    put("updatedAt", JSONObject().put("stringValue", prog.updatedAt.toString()))
                })
            }.toString()

            http.patchJson(patchUrl, patchBody, mapOf("Authorization" to "Bearer $token"))
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
            val itemId = fields.optJSONObject("itemId")?.optString("integerValue", "0")?.toLongOrNull() ?: 0L
            val profileId = fields.optJSONObject("profileId")?.optString("integerValue", "1")?.toLongOrNull() ?: 1L
            val positionMs = fields.optJSONObject("positionMs")?.optString("integerValue", "0")?.toLongOrNull() ?: 0L
            val durationMs = fields.optJSONObject("durationMs")?.optString("integerValue", "1")?.toLongOrNull() ?: 1L
            val updatedAt = fields.optJSONObject("updatedAt")?.optString("stringValue", "0")?.toLongOrNull() ?: 0L

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
