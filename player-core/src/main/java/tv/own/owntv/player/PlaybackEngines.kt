package tv.own.owntv.player

class PlaybackEngines(
    val player: OwnTVPlayer? = null,
    val livePreview: LivePreviewEngine? = null,
    val pool: Any? = null,
) {
    fun onTrimMemory(level: Int) {}
}
