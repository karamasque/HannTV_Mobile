package tv.own.owntv.mobile.ui.account

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.account.CloudAccountManager
import tv.own.owntv.core.account.CloudAuthResult
import tv.own.owntv.mobile.ui.components.BrandLockup

@Composable
fun MandatoryAuthGate(
    modifier: Modifier = Modifier,
    accountManager: CloudAccountManager = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isAuthLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF020617),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BrandLockup(markSize = 40, textSize = 26)

                Text(
                    text = if (isRegister) "HanTV Üyeliği Oluşturun" else "HanTV Hesabınıza Giriş Yapın",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = "Uygulamayı kullanabilmek için lütfen giriş yapın veya ücretsiz üyelik oluşturun.\nEn fazla 3 cihaz bağlayabilirsiniz.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; errorMessage = null },
                    label = { Text("E-posta Adresi") },
                    placeholder = { Text("ör. ahmet@gmail.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("Şifre") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = {
                        if (email.isBlank() || password.isBlank()) {
                            errorMessage = "Lütfen e-posta ve şifrenizi girin."
                            return@Button
                        }
                        isAuthLoading = true
                        errorMessage = null
                        scope.launch {
                            val res = if (isRegister) accountManager.register(email, password) else accountManager.login(email, password)
                            isAuthLoading = false
                            when (res) {
                                is CloudAuthResult.Success -> {
                                    Toast.makeText(context, "Hoş geldiniz!", Toast.LENGTH_SHORT).show()
                                }
                                is CloudAuthResult.Error -> {
                                    errorMessage = res.message
                                }
                            }
                        }
                    },
                    enabled = !isAuthLoading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (isAuthLoading) "İşlem Yapılıyor..." else if (isRegister) "Kayıt Ol" else "Giriş Yap")
                }

                TextButton(
                    onClick = {
                        isRegister = !isRegister
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isRegister) "Zaten hesabınız var mı? Giriş Yapın" else "Hesabınız yok mu? Üye Olun")
                }
            }
        }
    }
}
