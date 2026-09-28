package tv.own.owntv.core.settings

object TimeshiftRules {
    const val DEFAULT_WINDOW_MINUTES = 60
    val WINDOW_CHOICES_MINUTES: List<Int> = listOf(15, 30, 60, 120, 240, 480, 720, 1440)
    val choicesMinutes: List<Int> = WINDOW_CHOICES_MINUTES
    fun isSupported(): Boolean = true
}
