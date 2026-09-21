package tv.own.owntv.core.theme

/**
 * The one place HanTV's colour values are written down, shared by the TV app and the mobile app.
 *
 * Values are ARGB longs rather than `androidx.compose.ui.graphics.Color` because core carries the
 * Compose *runtime* only — no `compose-ui` — and because the two apps build different colour
 * schemes from them (`androidx.tv.material3` on the TV, `androidx.compose.material3` here). Each
 * app wraps a value once: `Color(HanTVPalette.DarkBackground)`.
 *
 * Dark uses a near-black background (#040e0b) so the panel colours pop against the deep dark
 * surface while keeping a subtle green undertone. NEUTRAL and the secondary/tertiary roles are
 * theme-only; the `primary` roles are seeded per [AccentColor] (default teal).
 *
 * The three `*RailPanel` / `*ContentPanel` / `*PreviewPanel` values are not part of the M3 ladder:
 * they are the shell's per-region colour identity, keeping the navigation, the content and the
 * detail pane distinct in both themes instead of collapsing into the greyer generic elevation
 * steps. Both apps draw those same three regions, so they belong here rather than in either one.
 */
typealias OwnTVPalette = HanTVPalette

object HanTVPalette {

    /** Brand mark colour (the HanTV play logo) — vibrant Electric Cyan / Neon Sky. */
    const val AccentCyan = 0xFF00D2FFL

    // ---------------- DARK (Modern Premium Slate Navy #121826) ----------------
    const val DarkBackground = 0xFF121826L
    const val DarkSurface = 0xFF1B2234L
    const val DarkSurfaceContainerLowest = 0xFF101522L
    const val DarkSurfaceContainerLow = 0xFF1F283DL
    const val DarkSurfaceContainer = 0xFF242E46L
    const val DarkSurfaceContainerHigh = 0xFF2B3752L
    const val DarkSurfaceContainerHighest = 0xFF344262L
    const val DarkRailPanel = 0xFF161D2EL
    const val DarkContentPanel = 0xFF131928L
    const val DarkPreviewPanel = 0xFF182032L
    const val DarkOnSurface = 0xFFFFFFFFL
    const val DarkOnSurfaceVariant = 0xFFCBD5E1L
    const val DarkOutline = 0xFF475569L
    const val DarkOutlineVariant = 0xFF334155L
    const val DarkSecondary = 0xFF93C5FDL
    const val DarkOnSecondary = 0xFF0F172AL
    const val DarkSecondaryContainer = 0xFF1E3A8AL
    const val DarkOnSecondaryContainer = 0xFFDBEAFEL
    const val DarkTertiary = 0xFF7DD3FCL
    const val DarkOnTertiary = 0xFF082F49L
    const val DarkTertiaryContainer = 0xFF0369A1L
    const val DarkOnTertiaryContainer = 0xFFE0F2FEL
    const val DarkError = 0xFFFF6B6BL

    // ---------------- LIGHT (M3 light) ----------------
    const val LightBackground = 0xFFF8FAFCL
    const val LightSurface = 0xFFF8FAFCL
    const val LightSurfaceContainerLowest = 0xFFFFFFFFL
    const val LightSurfaceContainerLow = 0xFFF1F5F9L
    const val LightSurfaceContainer = 0xFFE2E8F0L
    const val LightSurfaceContainerHigh = 0xFFCBD5E1L
    const val LightSurfaceContainerHighest = 0xFF94A3B8L
    const val LightRailPanel = 0xFFF1F5F9L
    const val LightContentPanel = 0xFFF8FAFCL
    const val LightPreviewPanel = 0xFFE2E8F0L
    const val LightOnSurface = 0xFF0F172AL
    const val LightOnSurfaceVariant = 0xFF475569L
    const val LightOutline = 0xFF64748BL
    const val LightOutlineVariant = 0xFFCBD5E1L
    const val LightSecondary = 0xFF0284C7L
    const val LightOnSecondary = 0xFFFFFFFFL
    const val LightSecondaryContainer = 0xFFE0F2FEL
    const val LightOnSecondaryContainer = 0xFF082F49L
    const val LightTertiary = 0xFF0369A1L
    const val LightOnTertiary = 0xFFFFFFFFL
    const val LightTertiaryContainer = 0xFFBAE6FDL
    const val LightOnTertiaryContainer = 0xFF082F49L
    const val LightError = 0xFFDC2626L
}

/**
 * The four M3 primary roles for one accent on one theme, as ARGB longs.
 *
 * They come either from a preset's table ([roles]) or are generated from the user's custom hex
 * seed ([accentRolesFromSeed]).
 */
data class AccentRoleValues(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
)

/**
 * The tonal palette each [AccentColor] seeds the M3 colour scheme with, for both themes (M3 uses
 * lighter tones on dark surfaces, darker tones on light).
 *
 * The display label belongs to whichever app is drawing the settings screen, so it is not here.
 */
private class AccentPalette(val dark: AccentRoleValues, val light: AccentRoleValues)

private val TealPalette = AccentPalette(
    dark = AccentRoleValues(0xFF00D2FFL, 0xFF082F49L, 0xFF0369A1L, 0xFFE0F2FEL),
    light = AccentRoleValues(0xFF0284C7L, 0xFFFFFFFFL, 0xFFBAE6FDL, 0xFF082F49L),
)

private val BluePalette = AccentPalette(
    dark = AccentRoleValues(0xFF38BDF8L, 0xFF082F49L, 0xFF0369A1L, 0xFFE0F2FEL),
    light = AccentRoleValues(0xFF0284C7L, 0xFFFFFFFFL, 0xFFBAE6FDL, 0xFF082F49L),
)

private val VioletPalette = AccentPalette(
    dark = AccentRoleValues(0xFFCBBEFFL, 0xFF312170L, 0xFF483A88L, 0xFFE7DEFFL),
    light = AccentRoleValues(0xFF5B45C9L, 0xFFFFFFFFL, 0xFFE5DEFFL, 0xFF190066L),
)

private val GreenPalette = AccentPalette(
    dark = AccentRoleValues(0xFF6FDB94L, 0xFF00391CL, 0xFF1F5135L, 0xFF8BF8AFL),
    light = AccentRoleValues(0xFF1B6B3FL, 0xFFFFFFFFL, 0xFFA6F2C0L, 0xFF00210FL),
)

private val AmberPalette = AccentPalette(
    dark = AccentRoleValues(0xFFFFB95CL, 0xFF452B00L, 0xFF624000L, 0xFFFFDDB3L),
    light = AccentRoleValues(0xFF8A5100L, 0xFFFFFFFFL, 0xFFFFDDB3L, 0xFF2C1600L),
)

/** The preset's four primary-role values for the given theme. */
fun AccentColor.roles(isDark: Boolean): AccentRoleValues {
    val palette = when (this) {
        AccentColor.TEAL -> TealPalette
        AccentColor.BLUE -> BluePalette
        AccentColor.VIOLET -> VioletPalette
        AccentColor.GREEN -> GreenPalette
        AccentColor.AMBER -> AmberPalette
    }
    return if (isDark) palette.dark else palette.light
}

/** Parses "#RRGGBB" / "RRGGBB" (also 8-digit AARRGGBB) into an ARGB long; null when invalid. */
fun parseAccentHex(hex: String): Long? {
    val s = hex.trim().removePrefix("#")
    return runCatching {
        when (s.length) {
            6 -> 0xFF000000L or s.toLong(16)
            8 -> s.toLong(16)
            else -> null
        }
    }.getOrNull()
}

/**
 * Generate tonal primary roles from an arbitrary seed colour (the custom hex accent).
 * The seed is used EXACTLY as `primary` so the user's hex renders true; only the supporting
 * contrast roles (onPrimary / containers) are derived by nudging the seed's lightness.
 */
fun accentRolesFromSeed(seed: Long, isDark: Boolean): AccentRoleValues {
    val argb = seed.toInt()
    // Choose a readable foreground for text/icons drawn on top of the exact seed colour.
    val onPrimary = if (androidx.core.graphics.ColorUtils.calculateLuminance(argb) > 0.5) {
        0xFF000000L
    } else {
        0xFFFFFFFFL
    }
    return if (isDark) {
        AccentRoleValues(seed, onPrimary, argb.withLightness(0.26f), argb.withLightness(0.90f))
    } else {
        AccentRoleValues(seed, onPrimary, argb.withLightness(0.88f), argb.withLightness(0.10f))
    }
}

/** Keep the seed's hue/saturation but pin the HSL lightness — a cheap stand-in for M3 tones. */
private fun Int.withLightness(l: Float): Long {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(this, hsl)
    hsl[2] = l
    return androidx.core.graphics.ColorUtils.HSLToColor(hsl).toLong() and 0xFFFFFFFFL
}
