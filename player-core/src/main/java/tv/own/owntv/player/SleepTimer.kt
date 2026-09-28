package tv.own.owntv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SleepTimer(
    val active: Any? = null,
    val screenOff: Any? = null,
    val itemEnd: Any? = null,
) {
    enum class EndKind { EPISODE, MOVIE }

    var stopPlayback: (() -> Unit)? = null
    val remainingMs: StateFlow<Long?> = MutableStateFlow(null)
    val stopsAtItemEnd: StateFlow<Boolean> = MutableStateFlow(false)
    fun itemEndKind(): EndKind? = null

    fun start(ms: Long) {}
    fun startUntilItemEnd() {}
    fun cancel() {}

    companion object {
        val CHOICES_MINUTES = listOf(15, 30, 45, 60, 90, 120)
        val PRESETS_MINUTES = CHOICES_MINUTES

        fun minutesLeft(ms: Long): Int = ((ms + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }
}
