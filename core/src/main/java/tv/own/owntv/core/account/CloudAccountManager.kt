package tv.own.owntv.core.account

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.IOException

private const val TAG = "HanTVCloudAuth"
private const val FIREBASE_API_KEY = "AIzaSyCitUUP3TuBwQjZBIjn4fUQX171WFt-2vo"
private const val FIREBASE_PROJECT_ID = "hantv-cloud"
private const val MAX_DEVICES = 1

data class CloudUser(
    val uid: String,
    val email: String,
    val idToken: String,
    val plan: String = "free",
    val activeDevicesCount: Int = 0,
) {
    val isPremium: Boolean get() = plan.equals("premium", ignoreCase = true)
}

sealed interface CloudAuthResult {
    data class Success(val user: CloudUser) : CloudAuthResult
    data class Error(val message: String) : CloudAuthResult
}

class DeviceLimitExceededException(message: String) : Exception(message)

class CloudAccountManager(
    private val context: Context,
    private val http: HttpClient,
    private val settings: SettingsRepository,
) {
    private val _currentUser = MutableStateFlow<CloudUser?>(null)
    val currentUser: StateFlow<CloudUser?> = _currentUser.asStateFlow()

    private val _activeDevices = MutableStateFlow<List<CloudDevice>>(emptyList())
    val activeDevices: StateFlow<List<CloudDevice>> = _activeDevices.asStateFlow()

    data class CloudDevice(
        val id: String,
        val name: String,
        val platform: String,
        val lastActive: String,
    )

    val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val model = Build.MODEL.orEmpty().replace(" ", "_")
        "dev_${androidId}_$model".take(40)
    }

    val deviceName: String by lazy {
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (${if (isTv()) "TV" else "Mobile"})"
    }

    val devicePlatform: String by lazy {
        if (isTv()) "tv" else "mobile"
    }

    private fun isTv(): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature("android.hardware.type.television") || pm.hasSystemFeature("android.software.leanback")
    }

    suspend fun getValidToken(): String = withContext(Dispatchers.IO) {
        val currentToken = settings.cloudIdToken.first()
        val refreshToken = settings.cloudRefreshToken.first()

        if (refreshToken.isNotBlank()) {
            val refreshUrl = "https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY"
            val respStr = runCatching {
                http.postForm(refreshUrl, mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken))
            }.getOrNull()

            if (!respStr.isNullOrBlank() && !respStr.contains("error")) {
                val json = JSONObject(respStr)
                val newIdToken = json.optString("id_token").ifBlank { json.optString("access_token") }
                val newRefreshToken = json.optString("refresh_token")
                if (newIdToken.isNotBlank()) {
                    settings.setCloudIdToken(newIdToken)
                    if (newRefreshToken.isNotBlank()) {
                        settings.setCloudRefreshToken(newRefreshToken)
                    }
                    return@withContext newIdToken
                }
            }
        }
        return@withContext currentToken
    }

    suspend fun restoreSession(): CloudUser? = withContext(Dispatchers.IO) {
        val email = settings.cloudUserEmail.first()
        val uid = settings.cloudUserId.first()
        val token = getValidToken()

        if (email.isBlank() || uid.isBlank() || token.isBlank()) {
            _currentUser.value = null
            return@withContext null
        }

        val isAdmin = email.lowercase() == "admin@hantv.com" || email.lowercase() == "kilicemre3437@gmail.com"
        val docUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid?key=$FIREBASE_API_KEY"
        val docRespStr = runCatching { http.getText(docUrl, headers = mapOf("Authorization" to "Bearer $token")) }.getOrNull()
        var userPlan = if (isAdmin) "premium" else "free"
        var deviceCount = 0
        if (!docRespStr.isNullOrBlank() && !docRespStr.contains("NOT_FOUND")) {
            val docJson = JSONObject(docRespStr)
            if (docJson.has("fields")) {
                val fields = docJson.getJSONObject("fields")
                if (fields.has("devices") && fields.getJSONObject("devices").has("mapValue")) {
                    val map = fields.getJSONObject("devices").getJSONObject("mapValue").optJSONObject("fields") ?: JSONObject()
                    deviceCount = map.length()
                }
                if (!isAdmin && fields.has("plan") && fields.getJSONObject("plan").has("stringValue")) {
                    val p = fields.getJSONObject("plan").optString("stringValue", "free")
                    if (p.isNotBlank()) userPlan = p
                }
            }
        }

        val user = CloudUser(uid = uid, email = email, idToken = token, plan = userPlan, activeDevicesCount = deviceCount)
        _currentUser.value = user
        registerDeviceInFirestore(uid, token, isNewUser = false)
        fetchDevices(uid)
        user
    }

    suspend fun login(email: String, pass: String): CloudAuthResult = withContext(Dispatchers.IO) {
        try {
            val authUrl = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$FIREBASE_API_KEY"
            val authBody = JSONObject().apply {
                put("email", email)
                put("password", pass)
                put("returnSecureToken", true)
            }.toString()

            val authRespStr = http.postJson(authUrl, authBody) ?: throw IOException("Giriş yanıtı alınamadı")
            val authJson = JSONObject(authRespStr)

            if (authJson.has("error")) {
                val errObj = authJson.getJSONObject("error")
                val msg = errObj.optString("message", "Giriş başarısız")
                val userMsg = when {
                    msg.contains("EMAIL_NOT_FOUND") || msg.contains("INVALID_PASSWORD") -> "E-posta adresi veya şifre hatalı."
                    msg.contains("INVALID_EMAIL") -> "Geçersiz e-posta formatı."
                    else -> "Giriş hatası: $msg"
                }
                return@withContext CloudAuthResult.Error(userMsg)
            }

            val uid = authJson.getString("localId")
            val idToken = authJson.getString("idToken")
            val refreshToken = authJson.optString("refreshToken", "")
            val userEmail = authJson.optString("email", email)

            saveCredentials(userEmail, uid, idToken, refreshToken)

            val docUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid?key=$FIREBASE_API_KEY"
            val docRespStr = runCatching { http.getText(docUrl, headers = mapOf("Authorization" to "Bearer $idToken")) }.getOrNull()
            
            var devicesMap = JSONObject()
            var userPlan = "free"
            if (!docRespStr.isNullOrBlank() && !docRespStr.contains("NOT_FOUND")) {
                val docJson = JSONObject(docRespStr)
                if (docJson.has("fields")) {
                    val fields = docJson.getJSONObject("fields")
                    if (fields.has("devices") && fields.getJSONObject("devices").has("mapValue")) {
                        devicesMap = fields.getJSONObject("devices").getJSONObject("mapValue").optJSONObject("fields") ?: JSONObject()
                    }
                    if (fields.has("plan") && fields.getJSONObject("plan").has("stringValue")) {
                        val p = fields.getJSONObject("plan").optString("stringValue", "free")
                        if (p.isNotBlank()) userPlan = p
                    }
                }
            }

            var physicalDeviceCount = 0
            var existingCurrentDevice = false
            for (k in devicesMap.keys()) {
                val devObj = devicesMap.optJSONObject(k)?.optJSONObject("mapValue")?.optJSONObject("fields")
                val plat = devObj?.optJSONObject("platform")?.optString("stringValue", "mobile") ?: "mobile"
                if (!plat.equals("web", ignoreCase = true)) {
                    physicalDeviceCount++
                }
                if (k == deviceId) {
                    existingCurrentDevice = true
                }
            }
            val isPremiumUser = userPlan.equals("premium", ignoreCase = true) || userEmail.lowercase() == "admin@hantv.com" || userEmail.lowercase() == "kilicemre3437@gmail.com"
            val maxAllowedDevices = if (isPremiumUser) 3 else 1

            if (!existingCurrentDevice && physicalDeviceCount >= maxAllowedDevices) {
                return@withContext CloudAuthResult.Error(
                    "Cihaz Sınırı Aşıldı! Hesabınız (${if (isPremiumUser) "Premium" else "Ücretsiz"}) en fazla $maxAllowedDevices cihaz bağlamanıza izin verir. Lütfen Web Paneli'nden (https://www.hantv.com.tr) bağlı bir cihazı kaldırın."
                )
            }

            val user = CloudUser(uid = uid, email = userEmail, idToken = idToken, plan = if (isPremiumUser) "premium" else userPlan, activeDevicesCount = if (existingCurrentDevice) physicalDeviceCount else physicalDeviceCount + 1)
            _currentUser.value = user

            registerDeviceInFirestore(uid, idToken, isNewUser = false)
            fetchDevices(uid)

            CloudAuthResult.Success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            CloudAuthResult.Error(e.message ?: "Giriş yapılırken bağlantı hatası oluştu.")
        }
    }

    suspend fun register(email: String, pass: String): CloudAuthResult = withContext(Dispatchers.IO) {
        try {
            val authUrl = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$FIREBASE_API_KEY"
            val authBody = JSONObject().apply {
                put("email", email)
                put("password", pass)
                put("returnSecureToken", true)
            }.toString()

            val authRespStr = http.postJson(authUrl, authBody) ?: throw IOException("Kayıt yanıtı alınamadı")
            val authJson = JSONObject(authRespStr)

            if (authJson.has("error")) {
                val errObj = authJson.getJSONObject("error")
                val msg = errObj.optString("message", "Kayıt başarısız")
                val userMsg = when {
                    msg.contains("EMAIL_EXISTS") -> "Bu e-posta adresi zaten kayıtlı."
                    msg.contains("WEAK_PASSWORD") -> "Şifre en az 6 karakter olmalıdır."
                    else -> "Kayıt hatası: $msg"
                }
                return@withContext CloudAuthResult.Error(userMsg)
            }

            val uid = authJson.getString("localId")
            val idToken = authJson.getString("idToken")
            val refreshToken = authJson.optString("refreshToken", "")

            saveCredentials(email, uid, idToken, refreshToken)

            val user = CloudUser(uid = uid, email = email, idToken = idToken, activeDevicesCount = 1)
            _currentUser.value = user

            registerDeviceInFirestore(uid, idToken, isNewUser = true)
            fetchDevices(uid)

            CloudAuthResult.Success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Register error", e)
            CloudAuthResult.Error(e.message ?: "Kayıt olunurken bağlantı hatası oluştu.")
        }
    }

    private suspend fun registerDeviceInFirestore(uid: String, idToken: String, isNewUser: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val token = if (idToken.isNotBlank()) idToken else getValidToken()
            val userEmail = _currentUser.value?.email?.takeIf { it.isNotBlank() }
                ?: settings.cloudUserEmail.first().takeIf { it.isNotBlank() }
                ?: ""
            val isAdmin = userEmail.lowercase() == "admin@hantv.com" || userEmail.lowercase() == "kilicemre3437@gmail.com"

            val docUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid?key=$FIREBASE_API_KEY"
            val docRespStr = runCatching { http.getText(docUrl, headers = mapOf("Authorization" to "Bearer $token")) }.getOrNull()

            val existingDevicesMap = JSONObject()
            var currentPlan = if (isAdmin) "premium" else "free"

            if (!docRespStr.isNullOrBlank() && !docRespStr.contains("NOT_FOUND")) {
                val docJson = JSONObject(docRespStr)
                if (docJson.has("fields")) {
                    val fields = docJson.getJSONObject("fields")
                    if (fields.has("devices") && fields.getJSONObject("devices").has("mapValue")) {
                        val devFields = fields.getJSONObject("devices").getJSONObject("mapValue").optJSONObject("fields")
                        if (devFields != null) {
                            val keys = devFields.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                existingDevicesMap.put(k, devFields.getJSONObject(k))
                            }
                        }
                    }
                    if (!isAdmin && fields.has("plan") && fields.getJSONObject("plan").has("stringValue")) {
                        val p = fields.getJSONObject("plan").optString("stringValue", "free")
                        if (p.isNotBlank()) currentPlan = p
                    }
                }
            }

            val currentDeviceObj = JSONObject().apply {
                put("mapValue", JSONObject().apply {
                    put("fields", JSONObject().apply {
                        put("id", JSONObject().put("stringValue", deviceId))
                        put("name", JSONObject().put("stringValue", deviceName))
                        put("platform", JSONObject().put("stringValue", devicePlatform))
                        put("lastActive", JSONObject().put("stringValue", System.currentTimeMillis().toString()))
                        put("addedAt", JSONObject().put("stringValue", System.currentTimeMillis().toString()))
                    })
                })
            }
            existingDevicesMap.put(deviceId, currentDeviceObj)

            val mask = "updateMask.fieldPaths=devices&updateMask.fieldPaths=plan&updateMask.fieldPaths=email" + (if (isAdmin) "&updateMask.fieldPaths=role" else "")
            val patchUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid?$mask&key=$FIREBASE_API_KEY"
            
            val patchBody = JSONObject().apply {
                put("fields", JSONObject().apply {
                    put("email", JSONObject().put("stringValue", userEmail))
                    put("plan", JSONObject().put("stringValue", currentPlan))
                    if (isAdmin) {
                        put("role", JSONObject().put("stringValue", "admin"))
                    }
                    put("devices", JSONObject().apply {
                        put("mapValue", JSONObject().apply {
                            put("fields", existingDevicesMap)
                        })
                    })
                })
            }.toString()

            val resp = http.patchJson(patchUrl, patchBody, mapOf("Authorization" to "Bearer $token"))
            Log.d(TAG, "registerDeviceInFirestore response: $resp")
        } catch (e: Exception) {
            Log.e(TAG, "registerDeviceInFirestore error", e)
        }
    }

    suspend fun fetchDevices(uid: String) = withContext(Dispatchers.IO) {
        try {
            val token = getValidToken()
            val headers = if (!token.isNullOrBlank()) mapOf("Authorization" to "Bearer $token") else emptyMap()
            val docUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid?key=$FIREBASE_API_KEY"
            val docRespStr = runCatching { http.getText(docUrl, headers = headers) }.getOrNull() ?: return@withContext
            val docJson = JSONObject(docRespStr)
            val list = mutableListOf<CloudDevice>()
            if (docJson.has("fields")) {
                val fields = docJson.getJSONObject("fields")
                if (fields.has("devices") && fields.getJSONObject("devices").has("mapValue")) {
                    val map = fields.getJSONObject("devices").getJSONObject("mapValue").optJSONObject("fields") ?: JSONObject()
                    for (key in map.keys()) {
                        val devObj = map.getJSONObject(key).optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
                        val id = devObj.optJSONObject("id")?.optString("stringValue", key) ?: key
                        val name = devObj.optJSONObject("name")?.optString("stringValue", "Cihaz") ?: "Cihaz"
                        val platform = devObj.optJSONObject("platform")?.optString("stringValue", "mobile") ?: "mobile"
                        val lastActive = devObj.optJSONObject("lastActive")?.optString("stringValue", "") ?: ""
                        if (!platform.equals("web", ignoreCase = true)) {
                            list.add(CloudDevice(id, name, platform, lastActive))
                        }
                    }
                }
            }
            _activeDevices.value = list
            val cur = _currentUser.value
            if (cur != null) {
                val email = cur.email
                val isAdmin = email.trim().lowercase() in listOf("admin@hantv.com", "kilicemre3437@gmail.com")
                _currentUser.value = cur.copy(
                    plan = if (isAdmin) "premium" else cur.plan,
                    activeDevicesCount = list.size
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to fetch devices", e)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        _currentUser.value = null
        _activeDevices.value = emptyList()
        settings.setCloudUserEmail("")
        settings.setCloudUserId("")
        settings.setCloudIdToken("")
        settings.setCloudRefreshToken("")
    }

    private suspend fun saveCredentials(email: String, uid: String, token: String, refreshToken: String = "") {
        settings.setCloudUserEmail(email)
        settings.setCloudUserId(uid)
        settings.setCloudIdToken(token)
        if (refreshToken.isNotBlank()) {
            settings.setCloudRefreshToken(refreshToken)
        }
    }
}
