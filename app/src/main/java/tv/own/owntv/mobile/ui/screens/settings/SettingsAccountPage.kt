package tv.own.owntv.mobile.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.account.CloudAccountManager
import tv.own.owntv.core.account.CloudAuthResult
import tv.own.owntv.core.account.CloudSyncEngine
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.MobileButtonStyle
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.SettingRow

@Composable
fun SettingsAccountPage(
    modifier: Modifier = Modifier,
    accountManager: CloudAccountManager = org.koin.compose.koinInject(),
    syncEngine: CloudSyncEngine = org.koin.compose.koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUser by accountManager.currentUser.collectAsStateWithLifecycle()
    val activeDevices by accountManager.activeDevices.collectAsStateWithLifecycle()

    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isAuthLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }

    SettingsPage(modifier) {
        settingsNote(R.string.settings_account_description)

        if (currentUser == null) {
            settingsGroup(key = "auth") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (isRegister) "Yeni Bulut Hesabı Oluştur" else "HanTV Bulut Hesabına Giriş Yap",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Üyeliğiniz ile en fazla 3 cihaz bağlayabilir, IPTV listelerinizi ve kaldığınız yerden devam et sürelerini senkronize edebilirsiniz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("E-posta Adresi") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Şifre") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { isRegister = !isRegister }) {
                            Text(if (isRegister) "Zaten hesabınız var mı? Giriş Yap" else "Hesabınız yok mu? Kayıt Ol")
                        }

                        MobileButton(
                            text = if (isAuthLoading) "Bekleyin..." else (if (isRegister) "Kayıt Ol" else "Giriş Yap"),
                            onClick = {
                                if (email.isBlank() || password.isBlank()) {
                                    Toast.makeText(context, "Lütfen e-posta ve şifre girin", Toast.LENGTH_SHORT).show()
                                    return@MobileButton
                                }
                                isAuthLoading = true
                                scope.launch {
                                    val res = if (isRegister) {
                                        accountManager.register(email, password)
                                    } else {
                                        accountManager.login(email, password)
                                    }
                                    isAuthLoading = false
                                    when (res) {
                                        is CloudAuthResult.Success -> {
                                            Toast.makeText(context, "Giriş Başarılı!", Toast.LENGTH_SHORT).show()
                                            syncEngine.syncAll()
                                        }
                                        is CloudAuthResult.Error -> {
                                            errorMessage = res.message
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        } else {
            val user = currentUser!!
            settingsGroup(key = "user-profile") {
                SettingRow(
                    title = "Bulut Hesabı",
                    value = user.email,
                )
                SettingRow(
                    title = "Bağlı Cihazlar Sınırı",
                    value = "${activeDevices.size} / 3 Aktif Cihaz",
                )
            }

            settingsGroup(key = "devices-list") {
                Text(
                    text = "Aktif Bağlı Cihazlarınız",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                activeDevices.forEach { dev ->
                    MobileListRow(
                        title = dev.name + if (dev.id == accountManager.deviceId) " (Bu Cihaz)" else "",
                        subtitle = "Tür: ${dev.platform.uppercase()} — Cihaz ID: ${dev.id.take(12)}...",
                        leading = {
                            Icon(
                                imageVector = if (dev.platform == "tv") MobileIcons.Tv else MobileIcons.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    )
                }
            }

            settingsGroup(key = "actions") {
                SettingRow(
                    title = "Şimdi Bulut Senkronizasyonu Yap",
                    subtitle = "IPTV kaynaklarını ve izleme sürelerini senkronize eder",
                    onClick = {
                        isSyncing = true
                        scope.launch {
                            val ok = syncEngine.syncAll()
                            isSyncing = false
                            Toast.makeText(
                                context,
                                if (ok) "Bulut senkronizasyonu tamamlandı!" else "Senkronizasyon hatası oluştu",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                SettingRow(
                    title = "Oturumu Kapat",
                    subtitle = "Bu cihazdaki bulut üyelik oturumunu sonlandırır",
                    onClick = {
                        scope.launch {
                            accountManager.logout()
                            Toast.makeText(context, "Oturum kapatıldı", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Giriş Engellendi") },
            text = { Text(errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("Tamam")
                }
            },
        )
    }
}
