package tv.own.owntv.core.brand

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import tv.own.owntv.core.R

enum class AppIcon(
    val activitySuffix: String,
    @StringRes val label: Int,
    @DrawableRes val mark: Int,
    @DrawableRes val markSmall: Int,
    @DrawableRes val banner: Int,
    val accent: Long,
    val accentOnLight: Long,
) {
    PETROL("Petrol", R.string.app_icon_petrol, R.drawable.owntv_mark_petrol, R.drawable.owntv_mark_small_petrol, R.drawable.owntv_banner_petrol, 0xFF06B6D4L, 0xFF0891B2L),
    SUNFLOWER("Sunflower", R.string.app_icon_sunflower, R.drawable.owntv_mark_sunflower, R.drawable.owntv_mark_small_sunflower, R.drawable.owntv_banner_sunflower, 0xFFEAB308L, 0xFFCA8A04L),
    COBALT("Cobalt", R.string.app_icon_cobalt, R.drawable.owntv_mark_cobalt, R.drawable.owntv_mark_small_cobalt, R.drawable.owntv_banner_cobalt, 0xFF3B82F6L, 0xFF2563EBL),
    TOMATO("Tomato", R.string.app_icon_tomato, R.drawable.owntv_mark_tomato, R.drawable.owntv_mark_small_tomato, R.drawable.owntv_banner_tomato, 0xFFEF4444L, 0xFFDC2626L),
    BOARD("Board", R.string.app_icon_board, R.drawable.owntv_mark_board, R.drawable.owntv_mark_small_board, R.drawable.owntv_banner_board, 0xFF64748BL, 0xFF475569L),
    EGGSHELL("", R.string.app_icon_eggshell, R.drawable.owntv_mark_eggshell, R.drawable.owntv_mark_small_eggshell, R.drawable.owntv_banner_eggshell, 0xFF94A3B8L, 0xFF64748BL),
    OLIVE("Olive", R.string.app_icon_olive, R.drawable.owntv_mark_olive, R.drawable.owntv_mark_small_olive, R.drawable.owntv_banner_olive, 0xFF84CC16L, 0xFF65A30DL),
    OLIVE_CREAM("OliveCream", R.string.app_icon_olive_cream, R.drawable.owntv_mark_olive_cream, R.drawable.owntv_mark_small_olive_cream, R.drawable.owntv_banner_olive_cream, 0xFF84CC16L, 0xFF65A30DL);

    companion object {
        val DEFAULT: AppIcon = EGGSHELL
    }
}
