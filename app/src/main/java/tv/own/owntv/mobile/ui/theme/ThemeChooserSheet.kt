package tv.own.owntv.mobile.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.HanTVThemePreset
import tv.own.owntv.core.theme.HanTVThemePresetId
import tv.own.owntv.core.theme.HanTVThemePresets
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.MobileButtonStyle
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.sheetListHeight

@Composable
fun ThemeChooserSheet(
    settings: SettingsRepository,
    currentPresetId: HanTVThemePresetId? = null,
    onDismissRequest: () -> Unit,
    onPresetApplied: ((HanTVThemePreset) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf(currentPresetId ?: HanTVThemePresetId.MACOS_GLASS) }

    MobileBottomSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.theme_preset_dialog_title),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MobileDimens.ScreenPaddingH)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.theme_preset_dialog_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetListHeight()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(HanTVThemePresets.ALL, key = { it.id.name }) { preset ->
                    val isSelected = preset.id == selectedId
                    val accentColor = remember(preset.accentColorHex) {
                        try {
                            Color(AndroidColor.parseColor(preset.accentColorHex))
                        } catch (_: Exception) {
                            Color(0xFF22D3EE)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            )
                            .then(
                                if (isSelected) Modifier.border(
                                    width = 2.dp,
                                    color = accentColor,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                else Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(14.dp),
                                )
                            )
                            .clickable {
                                selectedId = preset.id
                            }
                            .padding(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            // Thumbnail Preview with Accent Bar
                            val resId = context.resources.getIdentifier(
                                preset.wallpaperDrawableName,
                                "drawable",
                                context.packageName,
                            )
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black),
                            ) {
                                if (resId != 0) {
                                    Image(
                                        painter = painterResource(resId),
                                        contentDescription = stringResource(preset.titleRes),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                                // Bottom Accent Color Bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .align(Alignment.BottomCenter)
                                        .background(accentColor),
                                )
                            }

                            // Title & Description
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(preset.titleRes),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(preset.subtitleRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Checkmark
                            if (isSelected) {
                                Icon(
                                    imageVector = MobileIcons.Check,
                                    contentDescription = "Selected",
                                    tint = accentColor,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Save / Dismiss Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MobileButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismissRequest,
                    style = MobileButtonStyle.TEXT,
                    modifier = Modifier.weight(1f),
                )
                MobileButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        val preset = HanTVThemePresets.findById(selectedId)
                        scope.launch {
                            preset.applyTheme(context, settings)
                            onPresetApplied?.invoke(preset)
                            onDismissRequest()
                        }
                    },
                    style = MobileButtonStyle.PRIMARY,
                    modifier = Modifier.weight(1.5f),
                )
            }
        }
    }
}
