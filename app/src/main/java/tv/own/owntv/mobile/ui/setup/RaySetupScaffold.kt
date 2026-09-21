package tv.own.owntv.mobile.ui.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.HanTVThemePresets
import tv.own.owntv.mobile.R

/**
 * Premium Ray IPTV-style setup scaffold:
 * - macOS window traffic light indicators (Red, Yellow, Green)
 * - Branded dynamic title tinted with current theme accent
 * - "Hoş geldiniz" frosted glass pill
 * - Step badge e.g. "Görünüm (2/4)"
 * - 4-segment animated accent progress bar
 * - Central rounded liquid glass card container
 * - Bottom action buttons (Geri / İlerle)
 */
@Composable
fun RaySetupScaffold(
    stepIndex: Int, // 1-based index: 1, 2, 3, 4
    totalSteps: Int = 4,
    stepTitle: String,
    onBack: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    nextText: String = stringResource(R.string.setup_next),
    backText: String = stringResource(R.string.setup_back),
    nextEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val bgImagePath by settings.bgImagePath.collectAsStateWithLifecycle("")
    val activePreset = remember(bgImagePath) {
        HanTVThemePresets.ALL.firstOrNull { bgImagePath.contains(it.id.name.lowercase()) } ?: HanTVThemePresets.ALL.first()
    }
    val targetAccent = remember(activePreset.accentColorHex) {
        runCatching { Color(android.graphics.Color.parseColor(activePreset.accentColorHex)) }
            .getOrDefault(Color(0xFF38BDF8))
    }
    val accentColor by animateColorAsState(targetAccent, animationSpec = tween(300), label = "accent")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // --- Header Row ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: macOS Traffic Lights + App Title + Sub-pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // macOS Traffic Lights
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(11.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                    Box(Modifier.size(11.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                    Box(Modifier.size(11.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                }

                // Branded Title
                Text(
                    text = stringResource(R.string.app_name),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = accentColor,
                )

                // "Hoş geldiniz" Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33FFFFFF))
                        .border(0.8.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setup_welcome_tagline_short),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }

            // Right: Step indicator e.g. "Görünüm (2/4)"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x28000000))
                    .border(0.8.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "$stepTitle ($stepIndex/$totalSteps)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        // --- 4-Segment Progress Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 1..totalSteps) {
                val isActive = i <= stepIndex
                val segmentColor = if (isActive) accentColor else Color.White.copy(alpha = 0.15f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(segmentColor),
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // --- Central Liquid Glass Card Container ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x520B101B))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            content()
        }

        // --- Bottom Actions Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Geri Button
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x351E293B))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = backText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            // İlerle / Tamamla Button
            if (onNext != null) {
                val btnBg = if (nextEnabled) {
                    Brush.horizontalGradient(
                        listOf(accentColor.copy(alpha = 0.85f), accentColor.copy(alpha = 0.95f)),
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(Color(0x25FFFFFF), Color(0x25FFFFFF)),
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(btnBg)
                        .border(
                            1.dp,
                            if (nextEnabled) accentColor else Color.White.copy(alpha = 0.1f),
                            RoundedCornerShape(14.dp),
                        )
                        .clickable(enabled = nextEnabled, onClick = onNext)
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = nextText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (nextEnabled) Color.Black else Color.White.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
