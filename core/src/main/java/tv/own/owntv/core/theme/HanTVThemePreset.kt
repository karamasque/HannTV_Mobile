package tv.own.owntv.core.theme

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.R
import tv.own.owntv.core.settings.SettingsRepository
import java.io.File
import java.io.FileOutputStream

enum class HanTVThemePresetId {
    MACOS_GLASS,
    OS27,
    GLASS_GRI,
    MAC_TEMA,
    GLASSMORPHISM,
    MINT,
    MACOS_TV,
    KOYU_CAM,
    AMOLED_BLACK,
    FLY_UI,
    SEMC,
    DARK_FLAT,
    ACIK_CAM,
}

data class HanTVThemePreset(
    val id: HanTVThemePresetId,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @DrawableRes val wallpaperResId: Int,
    val accentColorHex: String,
    val accentColor: AccentColor,
    val isDark: Boolean = true,
    val glassAlphaPct: Int = 35,
    val glassBlurPct: Int = 60,
) {
    suspend fun extractWallpaper(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val destDir = File(context.filesDir, "backgrounds").apply { mkdirs() }
            val destFile = File(destDir, "${id.name.lowercase()}.webp")
            if (!destFile.exists() || destFile.length() == 0L) {
                context.resources.openRawResource(wallpaperResId).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            destFile
        } catch (_: Exception) {
            null
        }
    }

    suspend fun applyTheme(context: Context, settings: SettingsRepository) {
        val file = extractWallpaper(context)
        if (file != null && file.exists()) {
            settings.setBgImagePath(file.absolutePath)
        }
        settings.setCustomAccent(accentColorHex)
        settings.setAccent(accentColor)
        settings.setThemeMode(if (isDark) ThemeMode.DARK else ThemeMode.LIGHT)
        settings.setGlassAlphaPercent(glassAlphaPct, glassBlurPct)
        val allSurfacesBitmask = GlassConfig(scope = GlassSurface.entries.toSet()).toBitmask()
        settings.setGlassScopeBitmask(allSurfacesBitmask)
    }
}

object HanTVThemePresets {
    val ALL: List<HanTVThemePreset> = listOf(
        HanTVThemePreset(
            id = HanTVThemePresetId.MACOS_GLASS,
            titleRes = R.string.theme_macos_glass,
            subtitleRes = R.string.theme_macos_glass_desc,
            wallpaperResId = R.drawable.wallpaper_macos_glass,
            accentColorHex = "#64D2FF",
            accentColor = AccentColor.BLUE,
            isDark = true,
            glassAlphaPct = 25,
            glassBlurPct = 65,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.OS27,
            titleRes = R.string.theme_os27,
            subtitleRes = R.string.theme_os27_desc,
            wallpaperResId = R.drawable.wallpaper_ios27,
            accentColorHex = "#8B5CF6",
            accentColor = AccentColor.VIOLET,
            isDark = true,
            glassAlphaPct = 30,
            glassBlurPct = 60,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.GLASS_GRI,
            titleRes = R.string.theme_glass_gri,
            subtitleRes = R.string.theme_glass_gri_desc,
            wallpaperResId = R.drawable.wallpaper_glass_gri,
            accentColorHex = "#94A3B8",
            accentColor = AccentColor.TEAL,
            isDark = true,
            glassAlphaPct = 35,
            glassBlurPct = 50,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.MAC_TEMA,
            titleRes = R.string.theme_mac_tema,
            subtitleRes = R.string.theme_mac_tema_desc,
            wallpaperResId = R.drawable.wallpaper_mac_tema,
            accentColorHex = "#219BF0",
            accentColor = AccentColor.BLUE,
            isDark = true,
            glassAlphaPct = 35,
            glassBlurPct = 50,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.GLASSMORPHISM,
            titleRes = R.string.theme_glassmorphism,
            subtitleRes = R.string.theme_glassmorphism_desc,
            wallpaperResId = R.drawable.wallpaper_glassmorphism,
            accentColorHex = "#7EB6FF",
            accentColor = AccentColor.BLUE,
            isDark = true,
            glassAlphaPct = 30,
            glassBlurPct = 70,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.MINT,
            titleRes = R.string.theme_mint,
            subtitleRes = R.string.theme_mint_desc,
            wallpaperResId = R.drawable.wallpaper_mint,
            accentColorHex = "#10B981",
            accentColor = AccentColor.GREEN,
            isDark = true,
            glassAlphaPct = 35,
            glassBlurPct = 55,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.MACOS_TV,
            titleRes = R.string.theme_macos_tv,
            subtitleRes = R.string.theme_macos_tv_desc,
            wallpaperResId = R.drawable.wallpaper_macos_tv,
            accentColorHex = "#007AFF",
            accentColor = AccentColor.BLUE,
            isDark = true,
            glassAlphaPct = 40,
            glassBlurPct = 0,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.KOYU_CAM,
            titleRes = R.string.theme_koyu_cam,
            subtitleRes = R.string.theme_koyu_cam_desc,
            wallpaperResId = R.drawable.wallpaper_dark,
            accentColorHex = "#22D3EE",
            accentColor = AccentColor.TEAL,
            isDark = true,
            glassAlphaPct = 45,
            glassBlurPct = 60,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.AMOLED_BLACK,
            titleRes = R.string.theme_amoled_black,
            subtitleRes = R.string.theme_amoled_black_desc,
            wallpaperResId = R.drawable.wallpaper_amoled,
            accentColorHex = "#00E5FF",
            accentColor = AccentColor.TEAL,
            isDark = true,
            glassAlphaPct = 50,
            glassBlurPct = 0,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.FLY_UI,
            titleRes = R.string.theme_fly_ui,
            subtitleRes = R.string.theme_fly_ui_desc,
            wallpaperResId = R.drawable.wallpaper_fly_ui,
            accentColorHex = "#1BC9B8",
            accentColor = AccentColor.TEAL,
            isDark = true,
            glassAlphaPct = 35,
            glassBlurPct = 60,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.SEMC,
            titleRes = R.string.theme_semc,
            subtitleRes = R.string.theme_semc_desc,
            wallpaperResId = R.drawable.wallpaper_semc,
            accentColorHex = "#22C55E",
            accentColor = AccentColor.GREEN,
            isDark = true,
            glassAlphaPct = 40,
            glassBlurPct = 50,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.DARK_FLAT,
            titleRes = R.string.theme_dark_flat,
            subtitleRes = R.string.theme_dark_flat_desc,
            wallpaperResId = R.drawable.wallpaper_dark_flat,
            accentColorHex = "#06B6D4",
            accentColor = AccentColor.TEAL,
            isDark = true,
            glassAlphaPct = 50,
            glassBlurPct = 0,
        ),
        HanTVThemePreset(
            id = HanTVThemePresetId.ACIK_CAM,
            titleRes = R.string.theme_acik_cam,
            subtitleRes = R.string.theme_acik_cam_desc,
            wallpaperResId = R.drawable.wallpaper_light_glass,
            accentColorHex = "#007AFF",
            accentColor = AccentColor.BLUE,
            isDark = false,
            glassAlphaPct = 25,
            glassBlurPct = 60,
        ),
    )

    val PRESETS: List<HanTVThemePreset> = ALL

    fun findById(id: HanTVThemePresetId): HanTVThemePreset =
        ALL.firstOrNull { it.id == id } ?: ALL.first()
}
