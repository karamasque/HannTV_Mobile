package tv.own.owntv.core.live

import androidx.compose.runtime.Immutable
import java.text.DateFormat
import java.util.Date

/**
 * Now-playing EPG information for a channel row in lists and preview displays.
 */
@Immutable
data class ChannelNowPlaying(
    val title: String,
    val startMs: Long = 0L,
    val stopMs: Long = 0L,
) {
    fun formatDisplayText(timeFormat: DateFormat?): String {
        if (startMs <= 0 || timeFormat == null) return title
        val startTime = timeFormat.format(Date(startMs))
        val endTime = if (stopMs > startMs) timeFormat.format(Date(stopMs)) else null
        return if (endTime != null) {
            "$startTime - $endTime  $title"
        } else {
            "$startTime  $title"
        }
    }

    val progressFraction: Float
        get() {
            if (startMs <= 0 || stopMs <= startMs) return 0f
            val now = System.currentTimeMillis()
            val totalMs = (stopMs - startMs).toFloat()
            if (now in startMs..stopMs) {
                return ((now - startMs).toFloat() / totalMs).coerceIn(0f, 1f)
            }
            val tzOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
            val nowLocal = now + tzOffset
            if (nowLocal in startMs..stopMs) {
                return ((nowLocal - startMs).toFloat() / totalMs).coerceIn(0f, 1f)
            }
            val elapsedMs = (now - startMs).coerceAtLeast(0L)
            if (elapsedMs in 1 until (stopMs - startMs)) {
                return (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
            }
            return 0f
        }
}
