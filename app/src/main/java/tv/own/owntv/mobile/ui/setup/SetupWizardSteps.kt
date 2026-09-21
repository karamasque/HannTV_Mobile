package tv.own.owntv.mobile.ui.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.HanTVThemePresets
import tv.own.owntv.core.theme.UiFontScale
import tv.own.owntv.core.theme.UiZoom
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.MobileButtonStyle
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.screens.settings.SettingsSlider
import tv.own.owntv.mobile.ui.screens.settings.stepsFor
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.mobile.ui.theme.glassDialogWindow

/**
 * Step 1 — Language selection inside Ray IPTV glass container.
 */
@Composable
fun WelcomeStep(onNext: () -> Unit, localeStore: LocaleStore = koinInject()) {
    val scope = rememberCoroutineScope()
    val currentTag by localeStore.currentTag.collectAsStateWithLifecycle()
    val rows = remember { SupportedLocales.pickerRows.sortedBy { it.englishName } }
    val systemLabel = stringResource(R.string.settings_language_system_default)

    RaySetupScaffold(
        stepIndex = 1,
        stepTitle = stringResource(R.string.setup_step_language),
        onNext = onNext,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                item(key = "system") {
                    val isSelected = currentTag.isEmpty()
                    LanguageGlassRow(
                        title = systemLabel,
                        subtitle = null,
                        isSelected = isSelected,
                        onClick = {
                            scope.launch { runCatching { localeStore.set("") } }
                        },
                    )
                }

                items(rows, key = { it.languageTag }) { row ->
                    val isSelected = currentTag == row.languageTag
                    LanguageGlassRow(
                        title = row.endonym,
                        subtitle = row.englishName,
                        isSelected = isSelected,
                        onClick = {
                            scope.launch { runCatching { localeStore.set(row.languageTag) } }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageGlassRow(
    title: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
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
    val accentColor by animateColorAsState(targetAccent, animationSpec = tween(300), label = "lang_accent")

    val bg = if (isSelected) {
        Brush.horizontalGradient(
            listOf(accentColor.copy(alpha = 0.35f), accentColor.copy(alpha = 0.15f)),
        )
    } else {
        Brush.horizontalGradient(
            listOf(Color(0x221E293B), Color(0x181E293B)),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(accentColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MobileIcons.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            )
        }
    }
}

/**
 * Step 2 — Ray IPTV Style Vertical Theme & Wallpaper Customizer.
 */
@Composable
fun ThemeStep(onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val bgImagePath by settings.bgImagePath.collectAsStateWithLifecycle("")
    val activePreset = remember(bgImagePath) {
        HanTVThemePresets.ALL.firstOrNull { bgImagePath.contains(it.id.name.lowercase()) } ?: HanTVThemePresets.ALL.first()
    }

    RaySetupScaffold(
        stepIndex = 2,
        stepTitle = stringResource(R.string.setup_step_theme),
        onBack = onBack,
        onNext = onNext,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.theme_setup_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.theme_setup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(HanTVThemePresets.ALL, key = { it.id }) { preset ->
                    val isSelected = preset.id == activePreset.id
                    val parsedAccent = remember(preset.accentColorHex) {
                        runCatching { Color(android.graphics.Color.parseColor(preset.accentColorHex)) }
                            .getOrDefault(Color(0xFF64D2FF))
                    }

                    val rowBg = if (isSelected) {
                        Brush.horizontalGradient(
                            listOf(parsedAccent.copy(alpha = 0.40f), parsedAccent.copy(alpha = 0.16f)),
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(Color(0x281E293B), Color(0x1C1E293B)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(rowBg)
                            .border(
                                width = if (isSelected) 1.8.dp else 1.dp,
                                color = if (isSelected) parsedAccent else Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable {
                                scope.launch(Dispatchers.IO) { preset.applyTheme(context, settings) }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(preset.titleRes),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = stringResource(preset.subtitleRes),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(parsedAccent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MobileIcons.Check,
                                    contentDescription = null,
                                    tint = if (preset.isDark) Color.Black else Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Step 3 — Display and Font Size in Ray IPTV glass container.
 */
@Composable
fun DisplaySizeStep(onNext: () -> Unit, onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val zoom by settings.uiZoomPercent.collectAsStateWithLifecycle(UiZoom.DEFAULT)
    val fonts by settings.fontCustomization.collectAsStateWithLifecycle(FontCustomization())
    var pendingLowZoom by remember { mutableStateOf<Int?>(null) }
    var lowZoomAccepted by remember { mutableStateOf(zoom < UiZoom.LOW_RAM_WARN) }

    RaySetupScaffold(
        stepIndex = 3,
        stepTitle = stringResource(R.string.setup_step_display),
        onBack = onBack,
        onNext = onNext,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.setup_display_size_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.setup_display_size_description),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
            )

            SettingsSlider(
                title = stringResource(R.string.settings_ui_zoom),
                value = zoom,
                range = UiZoom.MIN..UiZoom.MAX,
                steps = stepsFor(UiZoom.MIN, UiZoom.MAX, UiZoom.STEP),
                onValueChange = { raw ->
                    val snapped = ((raw + UiZoom.STEP / 2) / UiZoom.STEP) * UiZoom.STEP
                    if (snapped < UiZoom.LOW_RAM_WARN && !lowZoomAccepted) {
                        pendingLowZoom = snapped
                    } else {
                        scope.launch { settings.setUiZoomPercent(snapped) }
                    }
                },
                subtitle = "$zoom%",
            )

            SettingsSlider(
                title = stringResource(R.string.settings_font_size),
                value = fonts.sizePercent,
                range = UiFontScale.MIN..UiFontScale.MAX,
                steps = stepsFor(UiFontScale.MIN, UiFontScale.MAX, UiFontScale.STEP),
                onValueChange = { raw ->
                    val snapped = ((raw + UiFontScale.STEP / 2) / UiFontScale.STEP) * UiFontScale.STEP
                    scope.launch {
                        settings.setFontCustomization(fonts.copy(sizePercent = snapped))
                    }
                },
                subtitle = "${fonts.sizePercent}%",
            )

            // Live Readability Preview Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22FFFFFF))
                    .border(0.8.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.setup_display_size_preview),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    pendingLowZoom?.let { target ->
        AlertDialog(
            modifier = Modifier.glassDialogWindow(),
            onDismissRequest = { pendingLowZoom = null },
            title = { Text(stringResource(R.string.settings_low_zoom_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_low_zoom_warning,
                        UiZoom.LOW_RAM_WARN,
                        UiZoom.LOW_RAM_WARN,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lowZoomAccepted = true
                        pendingLowZoom = null
                        scope.launch { settings.setUiZoomPercent(target) }
                    },
                ) { Text(stringResource(R.string.settings_low_zoom_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLowZoom = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Step 4 — Disclaimer screen before profile / playlist setup. */
@Composable
fun DisclaimerStep(onAgree: () -> Unit, onBack: () -> Unit) {
    RaySetupScaffold(
        stepIndex = 4,
        stepTitle = stringResource(R.string.setup_step_source),
        onBack = onBack,
        onNext = onAgree,
        nextText = stringResource(R.string.setup_i_understand),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.setup_before_you_start),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.setup_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )
        }
    }
}

/**
 * Step 5 — where the content comes from.
 */
@Composable
fun AddContentStep(
    hasExisting: Boolean,
    onNew: () -> Unit,
    onExisting: () -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)?,
) {
    SetupPage {
        Text(
            text = stringResource(R.string.setup_add_playlist),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.setup_add_playlist_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MobileDimens.GapSmall))
        MobileListRow(
            title = stringResource(R.string.setup_new),
            subtitle = stringResource(R.string.setup_add_m3u_xtream),
            leading = { Icon(MobileIcons.PlaylistAdd, contentDescription = null) },
            onClick = onNew,
        )
        if (hasExisting) {
            MobileListRow(
                title = stringResource(R.string.setup_existing),
                subtitle = stringResource(R.string.setup_use_other_profile_playlists),
                leading = { Icon(MobileIcons.PlaylistPlay, contentDescription = null) },
                onClick = onExisting,
            )
        }
        MobileListRow(
            title = stringResource(R.string.setup_import),
            subtitle = stringResource(R.string.setup_restore_backup_file),
            leading = { Icon(MobileIcons.Restore, contentDescription = null) },
            onClick = onImport,
        )
        MobileButton(
            text = stringResource(R.string.setup_skip_for_now),
            onClick = onSkip,
            style = MobileButtonStyle.TEXT,
        )
        if (onBack != null) {
            MobileButton(
                text = stringResource(R.string.common_back),
                onClick = onBack,
                style = MobileButtonStyle.TEXT,
            )
        }
    }
}

@Composable
fun ExistingSourcesStep(
    sources: List<SourceEntity>,
    onAdd: (Set<Long>) -> Unit,
    onBack: () -> Unit,
) {
    var selected by remember(sources) { mutableStateOf(sources.map { it.id }.toSet()) }

    SetupPage {
        Text(
            text = stringResource(R.string.setup_use_existing_playlists),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.setup_pick_playlists),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MobileDimens.GapSmall))
        LazyColumn(Modifier.heightIn(max = ExistingListMaxHeight)) {
            items(sources, key = { it.id }) { source ->
                val checked = source.id in selected
                MobileListRow(
                    title = source.name,
                    subtitle = source.type.name,
                    leading = {
                        Icon(
                            imageVector = if (checked) MobileIcons.Check else MobileIcons.PlaylistPlay,
                            contentDescription = null,
                            tint = if (checked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    onClick = {
                        selected = if (checked) selected - source.id else selected + source.id
                    },
                )
            }
        }
        Spacer(Modifier.height(MobileDimens.GapSmall))
        MobileButton(
            text = pluralStringResource(
                R.plurals.setup_add_selected_playlists,
                selected.size,
                selected.size,
            ),
            onClick = { onAdd(selected) },
            enabled = selected.isNotEmpty(),
        )
        MobileButton(
            text = stringResource(R.string.common_back),
            onClick = onBack,
            style = MobileButtonStyle.TEXT,
        )
    }
}

private val ExistingListMaxHeight = 320.dp
