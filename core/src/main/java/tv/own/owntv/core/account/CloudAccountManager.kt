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
import java.io.IOException

private const val TAG = "HanTVCloudAuth"
private const val FIREBASE_API_KEY = "AIzaSyD-HanTVCloudSyncSecretKey2026"
private const val FIREBASE_PROJECT_ID = "hantv-cloud"
private const val MAX_DEVICES = 3

data class CloudUser(
    val uid: String,
    val email: String,
    val idToken: String,
    val activeDevicesCount: Int = 0,
)

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
            val userEmail = authJson.optString("email", email)

            val docUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid?key=$FIREBASE_API_KEY"
            val docRespStr = runCatching { http.getText(docUrl) }.getOrNull()
            
            var devicesMap = JSONObject()
            if (!docRespStr.isNullOrBlank() && !docRespStr.contains("NOT_FOUND")) {
                val docJson = JSONObject(docRespStr)
                if (docJson.has("fields")) {
                    val fields = docJson.getJSONObject("fields")
                    if (fields.has("devices") && fields.getJSONObject("devices").has("mapValue")) {
                        devicesMap = fields.getJSONObject("devices").getJSONObject("mapValue").optJSONObject("fields") ?: JSONObject()
                    }
                }
            }

            val deviceCount = devicesMap.length()
            val existingCurrentDevice = devicesMap.has(deviceId)

            if (!existingCurrentDevice && deviceCount >= MAX_DEVICES) {
                return@withContext CloudAuthResult.Error(
                    "Cihaz Sınırı Aşıldı! Hesabınıza en fazla $MAX_DEVICES cihaz bağlanabilir. Lütfen Web Paneli'nden bağlı bir cihazı kaldırın."
                )
            }

            registerDeviceInFirestore(uid, idToken)

            val user = CloudUser(uid = uid, email = userEmail, idToken = idToken, activeDevicesCount = if (existingCurrentDevice) deviceCount else deviceCount + 1)
            _currentUser.value = user
            saveCredentials(userEmail, uid, idToken)

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

            registerDeviceInFirestore(uid, idToken)

            val user = CloudUser(uid = uid, email = email, idToken = idToken, activeDevicesCount = 1)
            _currentUser.value = user
            saveCredentials(email, uid, idToken)

            CloudAuthResult.Success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Register error", e)
            CloudAuthResult.Error(e.message ?: "Kayıt olunurken bağlantı hatası oluştu.")
        }
    }

    private suspend fun registerDeviceInFirestore(uid: String, idToken: String) = withContext(Dispatchers.IO) {
        val patchUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid?updateMask.fieldPaths=devices.$deviceId&key=$FIREBASE_API_KEY"
        val patchBody = JSONObject().apply {
            put("fields", JSONObject().apply {
                put("devices", JSONObject().apply {
                    put("mapValue", JSONObject().apply {
                        put("fields", JSONObject().apply {
                            put(deviceId, JSONObject().apply {
                                put("mapValue", JSONObject().apply {
                                    put("fields", JSONObject().apply {
                                        put("id", JSONObject().put("stringValue", deviceId))
                                        put("name", JSONObject().put("stringValue", deviceName))
                                        put("platform", JSONObject().put("stringValue", devicePlatform))
                                        put("lastActive", JSONObject().put("stringValue", System.currentTimeMillis().toString()))
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }.toString()

        http.patchJson(patchUrl, patchBody, mapOf("Authorization" to "Bearer $idToken"))
    }

    suspend fun fetchDevices(uid: String) = withContext(Dispatchers.IO) {
        try {
            val docUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/users/$uid?key=$FIREBASE_API_KEY"
            val docRespStr = runCatching { http.getText(docUrl) }.getOrNull() ?: return@withContext
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
                        list.add(CloudDevice(id, name, platform, lastActive))
                    }
                }
            }
            _activeDevices.value = list
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
    }

    private suspend fun saveCredentials(email: String, uid: String, token: String) {
        settings.setCloudUserEmail(email)
        settings.setCloudUserId(uid)
        settings.setCloudIdToken(token)
    }
}
