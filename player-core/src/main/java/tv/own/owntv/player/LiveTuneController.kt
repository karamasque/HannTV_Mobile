package tv.own.owntv.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.ForceMpvStore
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.stalker.StreamUrlResolver

data class EnginePair(
    val exo: LivePreviewEngine,
    val player: OwnTVPlayer,
)

data class LocalTimeshiftState(
    val resumeAtWallMs: Long = 0L,
)

class LiveTuneController(
    private val scope: CoroutineScope,
    private val engines: EnginePair,
    private val host: CoreHost,
) : CoroutineScope by scope {
    class CoreHost(
        val context: Context,
        val settings: SettingsRepository,
        val sourceDao: SourceDao,
        val resolver: StreamUrlResolver,
        val forceMpvStore: ForceMpvStore,
        val engineStarted: () -> Unit = {},
    )

    private val _liveOnExo = MutableStateFlow(true)
    val liveOnExo: StateFlow<Boolean> = _liveOnExo

    private val _previousChannel = MutableStateFlow<ChannelEntity?>(null)
    val previousChannel: StateFlow<ChannelEntity?> = _previousChannel

    private val _localTimeshift = MutableStateFlow<LocalTimeshiftState?>(null)
    val localTimeshift: StateFlow<LocalTimeshiftState?> = _localTimeshift

    val localRewind: Any? = null

    fun dismissResumeOffer() {
        _localTimeshift.value = null
    }

    fun seekTimeshift(wallMs: Long) {
    }

    fun localGaps(): List<LongRange> = emptyList()

    private var currentChannel: ChannelEntity? = null
    private var currentSource: SourceEntity? = null
    private var currentResolvedUrl: String? = null

    fun toggleEngine() {
        val channel = currentChannel
        val source = currentSource
        val resolved = currentResolvedUrl
        stop()
        _liveOnExo.value = !_liveOnExo.value
        if (channel != null && resolved != null) {
            start(channel, source, resolved)
        }
    }

    fun stop() {
        engines.exo.stop()
        engines.player.stop()
    }

    fun cancelTune() {
        stop()
    }

    fun releaseForArchive() {
        stop()
    }

    fun start(channel: ChannelEntity, source: SourceEntity?, resolved: String) {
        currentChannel = channel
        currentSource = source
        currentResolvedUrl = resolved
        stop()
        if (_liveOnExo.value) {
            engines.exo.play(resolved, muted = false)
        } else {
            engines.player.play(resolved, channel.name, null, null, isLive = true)
        }
        host.engineStarted()
    }

    fun playTile(engine: LivePreviewEngine, channel: ChannelEntity, muted: Boolean) {
        engine.play(channel.streamUrl, muted = muted)
    }

    fun noteWatched(channel: ChannelEntity) {
        _previousChannel.value = channel
    }
}
