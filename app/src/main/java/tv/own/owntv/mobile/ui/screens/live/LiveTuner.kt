package tv.own.owntv.mobile.ui.screens.live

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.database.entity.playStreamUrl
import tv.own.owntv.core.database.entity.resolveStreamUrl
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.core.live.LiveArchiveUrls
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.live.LiveTimeshift
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.player.AudioOnlyStore
import tv.own.owntv.core.player.enginePinKey
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.cast.CastHandoff
import tv.own.owntv.mobile.cast.CastRequest
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.playback.DataSaverGate
import tv.own.owntv.mobile.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import tv.own.owntv.player.LiveProgramme
import tv.own.owntv.player.LiveExoWatchdog
import tv.own.owntv.player.LiveLadder
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.MpvPlaybackEngine
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackSession

/**
 * What is playing, and everything a screen needs to ask about it.
 *
 * It outlives every screen deliberately. The channel screen, the fullscreen player and the docked
 * mini player are three views of **one** stream, and a user who leaves the channel screen while a
 * match is on has not asked for it to stop — so the tuning state cannot belong to a view model that
 * dies with its route. Only [stop] ends playback.
 *
 * Live rewind is core's [LiveTimeshift], the same class the television uses: this app supplies the
 * archive URL and the play call, exactly as `LiveViewModel` does there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTuner(
    private val context: Context,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val historyDao: HistoryDao,
    private val profileDao: ProfileDao,
    private val favoriteDao: FavoriteDao,
    private val userDataWriter: tv.own.owntv.core.backup.UserDataWriter,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val streamUrlResolver: StreamUrlResolver,
    private val epgReader: LiveEpgReader,
    private val archiveUrls: LiveArchiveUrls,
    private val session: PlaybackSession,
    private val dataSaver: DataSaverGate,
    private val audioOnlyStore: AudioOnlyStore,
    private val cast: CastController,
    private val recordings: tv.own.owntv.core.recording.RecordingManager,
    /**
     * The second live engine (L2). Live played on mpv and nothing else here, which is why the HUD's
     * engine button was hidden for live: there was no engine to swap to. This is the same
     * [LivePreviewEngine] the television runs live on, and the same one Multiview gives each tile.
     */
    private val exo: LivePreviewEngine,
    /** Per-channel "compatibility mode" pins — the same store, and the same meaning, as the television's. */
    private val forceMpvStore: tv.own.owntv.core.player.ForceMpvStore,
    val player: OwnTVPlayer,
    /** Lets core's background catalogue drain know a playlist is in use. */
    private val watchSession: tv.own.owntv.core.live.WatchSession,
) : CastHandoff {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)


    // --- "Record what I'm watching" (Plan D, D3 mode b) -----------------------------------------


    /**
     * Start or stop recording the channel on screen, from its start time of *now*.
     *
     * **This fetches the channel itself rather than copying the open stream, and that is deliberate.**
     * The original design tapped mpv's `stream-record`, which costs no second connection — but that
     * copies bytes as they pass through mpv's stream layer, and an HLS channel never puts them there:
     * FFmpeg's `hls` demuxer opens each segment on its own. mpv accepted the instruction and wrote
     * nothing, every time, on every HLS channel — which on a normal IPTV playlist is all of them.
     *
     * So it goes through the same engine as a scheduled recording, and inherits its behaviour: it
     * costs one of the playlist's connections, it **keeps running** when the channel is changed or
     * the player is left, and Downloads → Live TV can stop it. The connection is why
     * `canRecordOn` is asked first — a refusal is a sentence, not a failed recording.
     */
    fun togglePlayerRecording() {
        scope.launch {
            val open = playerRecording.value
            if (open != null) {
                recordings.stop(open)
                return@launch
            }
            val ch = channel.value ?: return@launch
            val pid = settings.activeProfileId.first().takeIf { it >= 0 } ?: return@launch
            startRecordingNow(ch, pid, _nowNext.value?.now)
        }
    }

    /**
     * Record [channel] from now, whether or not it is the one on screen — the long-press entry in the
     * channel list. A channel the provider publishes no guide for never appears in the guide, so this
     * is the only way to record it at all.
     */
    fun recordNow(ch: tv.own.owntv.core.database.entity.ChannelEntity) {
        scope.launch {
            val pid = settings.activeProfileId.first().takeIf { it >= 0 } ?: return@launch
            startRecordingNow(ch, pid, if (ch.id == channel.value?.id) _nowNext.value?.now else null)
        }
    }

    private suspend fun startRecordingNow(
        ch: tv.own.owntv.core.database.entity.ChannelEntity,
        profileId: Long,
        programme: tv.own.owntv.core.parser.XtEpgEntry?,
    ): tv.own.owntv.core.database.entity.RecordingEntity? {
        if (recordings.canRecordOn(ch.sourceId) !is tv.own.owntv.core.live.StreamGrant.Allowed) return null
        val startMs = System.currentTimeMillis()
        // Start now: the programme is already under way and a live edge cannot be rewound, so the
        // pre-roll has nothing to reach back to. The end is the programme's own, padding included.
        val stopMs = programme
            ?.let { recordings.windowFor(it.startMs, it.stopMs).last }
            ?.takeIf { it > startMs }
            ?: (startMs + tv.own.owntv.core.recording.RecordingSchedule.NO_GUIDE_RUNTIME_MINUTES * 60_000L)
        return recordings.schedule(
            tv.own.owntv.core.database.entity.RecordingEntity(
                profileId = profileId,
                sourceId = ch.sourceId,
                channelId = ch.id,
                channelName = ch.name,
                channelIconUrl = ch.logoUrl,
                epgChannelId = ch.epgChannelId,
                streamUrl = ch.streamUrl,
                httpHeaders = ch.httpHeaders,
                title = programme?.title ?: ch.name,
                description = programme?.description,
                programmeStartMs = programme?.startMs ?: startMs,
                programmeStopMs = programme?.stopMs ?: stopMs,
                startMs = startMs,
                stopMs = stopMs,
            ),
        )
    }

    /**
     * The engine as the rest of the system sees it. Published to [session] whenever a stream starts,
     * withdrawn in [stop] — this class is the only thing that knows whether anything is playing at
     * all, so it is the only thing that can answer a call, a headphone unplug or a lockscreen button
     * correctly.
     */
    private val engine by lazy { MpvPlaybackEngine(player) }

    // --- Which engine is playing live (L2) -------------------------------------------------------

    private val _liveOnExo = MutableStateFlow(false)

    /**
     * Whether live is on ExoPlayer right now — false means mpv, and false is also every VOD case.
     *
     * The HUD reads this to decide which surface to show and which way its engine button flips. It is
     * the *actual* engine rather than the pin, because an automatic handover to mpv leaves a channel
     * running on mpv while still unpinned, and a button keyed off the pin would then do nothing.
     */
    val liveOnExo: StateFlow<Boolean> = _liveOnExo

    /** The live ExoPlayer engine, for the surface the player screen has to give it. */
    val exoEngine: LivePreviewEngine get() = exo

    /**
     * Whichever engine the HUD should be reading and driving.
     *
     * Both are a [PlaybackEngine], so nothing above this has to know which one it has — the same
     * arrangement the television uses, and the reason the phone's HUD needed no second set of
     * controls.
     */
    val activeEngine: StateFlow<PlaybackEngine> = _liveOnExo
        .map { onExo -> if (onExo) exo else engine }
        .stateIn(scope, SharingStarted.Eagerly, engine)

    /**
     * The same answer as [activeEngine], read straight from the flag instead of from the flow
     * derived off it.
     *
     * **Not the same thing as `activeEngine.value`, and the difference matters.** `stateIn` republishes
     * on its own coroutine, so between `_liveOnExo` changing and that coroutine running, the flow
     * still holds the engine that was playing a moment ago. Everything in this class acts immediately
     * after starting an engine — the sound-only default is applied on the very next line — and would
     * otherwise be talking to the one just stopped. The flow stays, because a composable has to be
     * able to *observe* the change; a caller that only needs the answer now uses this.
     */
    val currentEngine: PlaybackEngine get() = if (_liveOnExo.value) exo else engine

    /**
     * Whether anything is playing at all, asked of whichever engine would be holding it.
     *
     * `player.hasActiveStream` answers for mpv alone, and live opens on ExoPlayer by default — so
     * every lifecycle decision built on it was told "nothing is playing" for the whole of a live
     * channel: the screen was allowed to sleep, Picture-in-Picture never opened, and the
     * background-playback rules never ran. mpv still answers for a film, a download, a recording and
     * a channel pinned to compatibility mode, because those genuinely are its streams.
     */
    val hasStream: Boolean
        get() = if (_liveOnExo.value) exo.currentUrl != null else player.hasActiveStream

    /**
     * The shape of the picture, from whichever engine is drawing it, or null before one is known.
     *
     * Only the Picture-in-Picture window needs it out here — a 2.35:1 film given the fixed 16:9 the
     * window used to ask for is a small picture with a black band above and below it, in a window
     * that is already tiny. [tv.own.owntv.mobile.ui.player.VideoStage] resolves the same two engines
     * for the same reason.
     */
    val videoAspect: Float?
        get() = if (_liveOnExo.value) exo.videoAspect.value else player.videoAspect.value

    /** Cancelled by the next tune, a stop, or a manual engine switch. */
    private var exoWatchJob: Job? = null

    /**
     * Hand the stream to the system: the session takes the lockscreen and the audio focus, the
     * foreground service keeps the process alive once the app leaves the screen. Both are idempotent,
     * so every `play()` call can go through here.
     */
    private fun publishToSystem() {
        // Whichever engine actually holds the stream: a session published for the idle one would
        // answer the lockscreen and the headphone button for something that is not playing.
        session.attach(if (_liveOnExo.value) exo else engine)
        PlaybackService.start(context)
    }

    private val ctx: StateFlow<ActiveProfileSources> = activeProfileSources(settings, sourceDao)
        .stateIn(scope, SharingStarted.Eagerly, ActiveProfileSources(-1L, emptyList()))

    private val custom: StateFlow<SectionCustomizations> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(SectionCustomizations())
            else customize.observe(c.profileId, MediaType.LIVE)
        }
        .stateIn(scope, SharingStarted.Eagerly, SectionCustomizations())

    private val _channel = MutableStateFlow<ChannelEntity?>(null)

    /** The channel on screen, with the user's own name for it. */
    val channel: StateFlow<ChannelEntity?> = _channel

    init {
        // Tell core which playlist is on screen, so its background catalogue drain steps aside.
        //
        // Driven from the tuner, not from PlayerScreen: on a phone a channel starts playing from the
        // Live list and the full player composable is not mounted yet, so a hook there never fired —
        // measured on a single-connection portal, where the drain held the only stream and the
        // picture never arrived. The tuner owns playback whichever screen is showing.
        scope.launch {
            var held: Long? = null
            channel.collect { ch ->
                val next = ch?.sourceId
                if (next != held) {
                    held?.let { watchSession.close(it) }
                    next?.let { watchSession.open(it) }
                    held = next
                }
            }
        }
    }


    /**
     * The recording running on the channel on screen, or null.
     *
     * **Read from the table rather than held here.** It used to be a field set by the button, which
     * meant the player only knew about a recording *it* had started: one begun from the channel
     * list's long-press left the button saying "Record" while the channel was already recording, and
     * pressing it again would have started a second one. A recording no longer belongs to the
     * playing stream — it outlives it — so the honest question is "is this channel being recorded?",
     * and only the table can answer that.
     */
    val playerRecording: StateFlow<tv.own.owntv.core.database.entity.RecordingEntity?> =
        settings.activeProfileId
            .flatMapLatest { pid ->
                if (pid < 0) flowOf(emptyList()) else recordings.observe(pid)
            }
            .combine(channel) { rows, ch ->
                rows.firstOrNull {
                    it.channelId == ch?.id &&
                        it.status == tv.own.owntv.core.model.RecordingStatus.RECORDING
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val _nowNext = MutableStateFlow<EpgNowNext?>(null)
    val nowNext: StateFlow<EpgNowNext?> = _nowNext

    private val _replaying = MutableStateFlow(false)

    /**
     * True only while a *chosen* archive programme is playing — the one thing on this tuner that has
     * an end and therefore a seek bar.
     *
     * The player cannot be asked this. A live stream's duration is whatever the provider's rolling
     * window happens to report, which for plenty of them is a plausible-looking twenty-five hours, so
     * deciding live-ness from the duration classed real channels as recordings and hid the entire
     * live panel. This tuner is the thing that knows: it started the stream, and it knew which kind
     * it was asking for.
     *
     * A rewind into the archive from the live edge is deliberately **not** a replay — that is still
     * the channel, just behind, and it keeps the live bar and the way back to now.
     */
    val replaying: StateFlow<Boolean> = _replaying

    private val _timelineProgrammes = MutableStateFlow<List<LiveProgramme>>(emptyList())

    /**
     * The playing channel's guide window, for the player's live timeline: these become the programme
     * boundary ticks on the bar and the name the scrub bubble reads out. Loaded once per channel —
     * scrubbing must never re-query the guide, the whole window is already here.
     */
    val timelineProgrammes: StateFlow<List<LiveProgramme>> = _timelineProgrammes

    /** Whether the channel playing is a favourite — the floating window's menu, which has no list
     *  row behind it to ask. */
    val isFavorite: StateFlow<Boolean> = _channel
        .flatMapLatest { channel ->
            val pid = ctx.value.profileId
            if (channel == null || pid < 0) flowOf(false)
            else favoriteDao.isFavorite(pid, MediaType.LIVE, channel.id)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavorite() {
        val channel = _channel.value ?: return
        val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return
        scope.launch {
            if (isFavorite.value) userDataWriter.removeFavorite(pid, MediaType.LIVE, channel.id)
            else favoriteDao.add(FavoriteEntity(profileId = pid, mediaType = MediaType.LIVE, itemId = channel.id))
        }
    }

    private val _siblings = MutableStateFlow<List<ChannelEntity>>(emptyList())

    /** The other channels of the same folder — the Channels tab, and the swipe-up overlay. */
    val siblings: StateFlow<List<ChannelEntity>> = _siblings

    private val timeshift = LiveTimeshift(
        scope = scope,
        playback = object : LiveTimeshift.Playback {
            override val positionMs: Long get() = player.position.value
            override val hasError: Boolean get() = player.error.value != null
            override val hasActiveStream: Boolean get() = player.hasActiveStream
        },
        loadArchive = ::loadArchiveStream,
        onLiveEdge = { goToLive() },
    )

    /** Seconds behind the live edge; null at the edge — what the red bar and the pill read. */
    val offsetSec: StateFlow<Int?> = timeshift.offsetSec

    /** The wall-clock instant actually on screen while an archive plays. */
    val watchingWallMs: StateFlow<Long?> = timeshift.watchingWallMs

    private var loadedId: Long? = null

    /** The programme a replay is showing, so ending a cast can start the same one again rather than
     *  dropping the user at the live edge. Null whenever a replay is not what is playing. */
    private var lastCatchup: EpgProgrammeEntity? = null

    /** Open [channelId]: read the row, start it, and fill the guide and the channel list around it. */
    fun tune(channelId: Long) {
        // `hasStream`, not mpv's own: on live-on-ExoPlayer mpv always answers "nothing here", so
        // re-opening the channel you are already watching tore the stream down and rebuilt it —
        // a reconnection and a second of black for a screen you had just come back to.
        if (loadedId == channelId && hasStream) return
        loadedId = channelId
        scope.launch {
            val channel = withContext(Dispatchers.IO) { channelDao.getById(channelId) } ?: return@launch
            start(channel)
            loadSiblings(channel)
        }
    }

    /** Switch to another channel without leaving the screen — the Channels tab and the overlay. */
    /**
     * Make [channel] the current one **without playing it**, for a tap that is about to open
     * Multiview instead of the player.
     *
     * [switchTo] starts a stream, and the grid opens by stopping it again a fraction of a second
     * later — a race the stop lost: the engine was still loading when it was told to stop, carried
     * on, and played underneath the grid. That was the phone's doubled sound, named in a log as an
     * engine the pool had never built. Nothing should be started that is about to be stopped.
     */
    fun selectWithoutPlaying(channel: ChannelEntity) {
        loadedId = channel.id
        _channel.value = custom.value.itemNames[CustomizeKeys.channel(channel)]
            ?.let { channel.copy(name = it) } ?: channel
    }

    fun switchTo(channel: ChannelEntity) {
        if (channel.id == loadedId) return
        loadedId = channel.id
        scope.launch { start(channel) }
    }

    /**
     * The next (+1) or previous (−1) channel of the same folder, wrapping at both ends — Channel +/−
     * from the Picture-in-Picture window, where there is no room for a list.
     */
    fun step(delta: Int) {
        val list = _siblings.value
        if (list.size < 2) return
        val index = list.indexOfFirst { it.id == loadedId }
        if (index < 0) return
        switchTo(list[(index + delta).mod(list.size)])
    }

    private suspend fun start(channel: ChannelEntity) {
        // Before anything is claimed to be tuned: refusing has to leave the screen as it was, and the
        // channel forgotten, so tapping the same row again on Wi-Fi opens it.
        if (!dataSaver.allowsStreaming()) {
            loadedId = null
            return
        }
        timeshift.clear() // a new channel is never still rewound into the old one's archive
        _replaying.value = false
        lastCatchup = null
        // A renamed channel keeps its new name on this screen too — the row the user tapped had it.
        val named = custom.value.itemNames[CustomizeKeys.channel(channel)]?.let { channel.copy(name = it) } ?: channel
        _channel.value = named
        _nowNext.value = null
        _timelineProgrammes.value = emptyList()

        val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return
        if (!AdultCategoryClassifier.allows(pid, channel.categoryId, profileDao, categoryDao)) return

        val source = withContext(Dispatchers.IO) { sourceDao.getById(channel.sourceId) }
        // Stalker portals mint a play URL per tune; the stored "URL" is a portal command until then.
        val url = if (streamUrlResolver.needsResolve(source)) {
            runCatching { streamUrlResolver.resolve(source!!, channel.streamUrl) }.getOrNull() ?: return
        } else {
            channel.streamUrl
        }
        // The television first, if one has been picked. It plays the stream itself, so nothing below
        // about engines, sound-only or the local session applies to it.
        val handedOver = cast.offer(
            this,
            CastRequest(
                url = url,
                title = named.name,
                logoUrl = named.displayLogoUrl,
                isLive = true,
                httpHeaders = channel.httpHeaders,
            ),
        )
        if (!handedOver) {
            // L2 - which engine opens it, in the television's own descending order of authority.
            //
            // The phone used to skip the middle two entirely: it asked only whether the channel was
            // pinned and otherwise opened on ExoPlayer. So **Settings → Video player → Live TV
            // player did nothing here**, global or per playlist — "mpv only" still opened every
            // channel on ExoPlayer, waited for it to fail, and arrived at mpv the slow way.
            //
            //  1. **A protected channel is ExoPlayer's, always.** mpv ships no CDM, so it cannot ask
            //     a licence server for a key; handing one over could only produce a failure. It
            //     outranks the pin as well as the setting, because it is a fact about the stream.
            //  2. **The per-channel pin** — "compatibility mode", the user saying so about this one
            //     channel. It outranks the setting, which is the whole point of the button.
            //  3. **This playlist's own override**, then the global setting.
            val setting = enginePreferenceFor(source)
            val pinnedToMpv = pinKeyFor(channel) in forceMpvStore.urls.first()
            val protected = channel.drmConfig != null
            // Learned, rather than pinned: a panel already caught handing out signed segment URLs it
            // then refuses can never be satisfied by ExoPlayer, so go straight to mpv instead of
            // replaying the detection — two dead 403s and the wait — on every further channel of it.
            // The lesson is taught by core's own ExoPlayer engine, so the phone was already learning
            // it and simply never read it. Consulted only while both engines are allowed and the
            // channel is unpinned: a lesson the app taught itself may not overturn the user's choice.
            val refusing = !pinnedToMpv && setting.allowsHandover && panelRefusesSegments(channel, source)
            val onMpv = !protected && (pinnedToMpv || refusing || setting.startsOnMpv)
            // What the LADDER is armed with, which is not always the setting. A protected channel is
            // ExoPlayer-only whatever the setting says. A pin that contradicts an "only" setting
            // re-opens the handover for that one channel — otherwise the exception channel would be
            // locked to the engine the user has just said cannot play it, with the ladder forbidden
            // from ever reaching the one that can: a dead end of the app's own making.
            val preference = when {
                protected -> tv.own.owntv.core.player.EnginePreference.EXO_ONLY
                setting.allowsHandover -> tv.own.owntv.core.player.EnginePreference.firstOn(onMpv)
                onMpv == setting.startsOnMpv -> setting
                else -> tv.own.owntv.core.player.EnginePreference.firstOn(onMpv)
            }
            val why = when {
                protected -> "exoplayer (drm)"
                pinnedToMpv -> "mpv (pinned)"
                refusing -> "mpv (panel refuses segments)"
                else -> "${if (onMpv) "mpv" else "exoplayer"} (setting)"
            }
            engineLog("tune '${channel.name}' -> $why [${preference.name}]")
            armLadder(channel, source, preference)
            if (onMpv) startOnMpv(named, source, resolved = url) else startOnExo(named, source, resolved = url)
            applyAudioOnlyDefault(channel)
        }
        recordHistory(pid, channel.id)
        _nowNext.value = epgReader.nowNext(channel, custom.value, settings.epgOffsetMinutes.first())
        _timelineProgrammes.value = catchupProgrammes()
            .map { LiveProgramme(it.startMs, it.stopMs, it.title) }
    }

    /** The stable per-channel key a compatibility pin is filed under (P6), with the URL as fallback. */
    private fun pinKeyFor(channel: ChannelEntity): String =
        enginePinKey(channel.sourceId, MediaType.LIVE.name, channel.remoteId) ?: channel.streamUrl

    // --- The three per-playlist overrides, each falling back to the global setting ----------------
    //
    // All three are the television's, read the same way and resolved by the same core helpers, so a
    // playlist configured on one device means the same thing on the other after a sync. The phone
    // stored and displayed all three and passed none of them to an engine.

    /**
     * Which engine this channel's playlist wants, or the global setting when it has no opinion.
     *
     * Per playlist because which engine copes is a property of the provider's stream format, not of
     * the person watching.
     */
    private suspend fun enginePreferenceFor(source: tv.own.owntv.core.database.entity.SourceEntity?):
        tv.own.owntv.core.player.EnginePreference =
        source?.liveEnginePreference
            ?.let { name ->
                tv.own.owntv.core.player.EnginePreference.entries.firstOrNull { it.name == name }
            }
            ?: settings.liveEnginePreference.first()

    /** This playlist's "Pre-buffer" in seconds, or null to follow the global slider. */
    private fun prerollFor(source: tv.own.owntv.core.database.entity.SourceEntity?): Int? =
        source?.livePrerollSecs?.takeIf { it >= 0 }

    /**
     * This playlist's Live latency, or null to follow the global setting.
     *
     * Resolved through the same `LiveBuffer.effectiveSeconds` the global path uses, so a Custom value
     * is clamped identically and Balanced still means "the engine's own defaults" — for this
     * playlist rather than for all of them.
     */
    private fun liveBufferFor(source: tv.own.owntv.core.database.entity.SourceEntity?):
        tv.own.owntv.core.settings.LiveBuffer.Override? {
        val mode = source?.liveLatencyMode ?: return null
        return tv.own.owntv.core.settings.LiveBuffer.Override(
            tv.own.owntv.core.settings.LiveBuffer.effectiveSeconds(
                tv.own.owntv.core.settings.LiveLatency.fromName(mode),
                source.liveLatencyCustomSecs,
            ),
        )
    }

    /**
     * Open [channel] on mpv — a pinned channel, or ExoPlayer having given up on it.
     *
     * mpv is the full-screen player the rest of the app already uses, so this is the path the phone
     * has always taken; the only new thing is that it is now a choice rather than the only option.
     */
    private suspend fun startOnMpv(
        channel: ChannelEntity,
        /** The playlist, for its User-Agent and its three per-playlist overrides. */
        source: SourceEntity?,
        /** This rung is the `.ts` one: do not let "Prefer HLS" rewrite the URL. */
        forceTs: Boolean = false,
        /** A URL already minted for this tune, so the first rung does not resolve a Stalker link twice
         *  — minting one ends the portal's previous session. Later rungs pass null and mint afresh,
         *  because a link already spent on ExoPlayer is not one mpv can reuse. */
        resolved: String? = null,
    ) {
        // What ExoPlayer discovered before it let go: some panels redirect their advertised `.ts` to
        // HLS, and handing mpv that misleading URL traps FFmpeg at the manifest EOF. "Discovered"
        // strictly means ExoPlayer asked for something that was NOT HLS and got HLS anyway — playing
        // an `.m3u8` we deliberately requested teaches nothing about the `.ts` endpoint.
        val exoTuneUrl = exo.currentUrl
        val exoDiscoveredHls = _liveOnExo.value && exo.isHlsStream &&
            exoTuneUrl != null && !tv.own.owntv.player.LiveStreamQuirks.isExplicitHlsUrl(exoTuneUrl)
        exoWatchJob?.cancel()
        mpvOutcomeJob?.cancel()
        _liveOnExo.value = false
        // Free ExoPlayer's decoder and its connection BEFORE mpv asks for either. A one-connection
        // playlist refuses the second request outright, and a TV-class decoder hands mpv a codec the
        // outgoing engine still holds — the same ordering every other engine transition already uses.
        exo.stop()
        // Let ExoPlayer's decoder actually go before mpv initialises. Nothing exposes "the MediaCodec
        // is released", so the only alternative to waiting is guessing, and guessing short reproduces
        // the codec-claim failure this constant exists to prevent.
        //
        // Only when ExoPlayer had something, though. A tune that starts on mpv — "mpv first", or a
        // pinned channel — has no decoder to wait for, and paying half a second of black on every one
        // of them would be a handover cost charged to a tune that never handed over.
        if (exoTuneUrl != null) {
            kotlinx.coroutines.delay(OwnTVPlayer.SURFACE_HANDOFF_MS)
            if (loadedId != channel.id) {
                // The only exit that leaves the shell on mpv's surface with nothing loaded. Normal
                // when the user zapped during the release wait; in a support log it is the difference
                // between "the handoff was abandoned" and "the handoff vanished".
                engineLog("mpv handoff for '${channel.name}' abandoned — the channel changed while ExoPlayer released")
                return
            }
        }
        val raw = urlFor(channel, source, resolved) ?: return
        // Keyed to mpv's OWN verdict: ExoPlayer failing this channel's `.m3u8` says nothing about
        // whether mpv can play it, and on a traced channel mpv plays exactly the manifest ExoPlayer
        // cannot — which is why `mpv + HLS` is a rung of its own rather than a repeat.
        val preferred = if (forceTs || tv.own.owntv.player.LiveStreamQuirks.lacksHlsVariantMpv(channel.streamUrl)) {
            raw
        } else {
            resolveStreamUrl(raw, source)
        }
        // Recorded against the PANEL, not this one channel: the redirect is a property of the
        // provider, so every later channel starts out knowing this `.ts` is really HLS instead of
        // re-learning it the slow way.
        if (exoDiscoveredHls) tv.own.owntv.player.LiveStreamQuirks.rememberHlsRedirect(preferred)
        val url = if (tv.own.owntv.player.LiveStreamQuirks.isKnownHlsHost(preferred)) {
            tv.own.owntv.player.LiveStreamQuirks.toHlsUrl(preferred)
        } else {
            preferred
        }
        player.play(
            url = url,
            title = channel.name,
            logoUrl = channel.displayLogoUrl,
            isLive = true,
            userAgent = source?.userAgent,
            // The same stable key ExoPlayer files this channel under. Without it mpv filed the
            // channel by its stream URL instead, so a zoom or a volume set on one engine was
            // forgotten the moment the channel fell back to the other.
            contentKey = pinKeyFor(channel),
            httpHeaders = channel.httpHeaders,
            livePrerollSecsOverride = prerollFor(source),
            liveBufferOverride = liveBufferFor(source),
        )
        publishToSystem()
        // mpv is watched now too. Without this it was a terminus: a channel it could not open simply
        // sat there, because every fallback on the phone was written for the ExoPlayer-first direction.
        watchMpvOutcome(channel, source)
    }

    /**
     * The URL to actually open for [channel] — [resolved] when this tune already minted one, otherwise
     * the stored address, or a freshly minted Stalker link.
     *
     * Minting is not free and not repeatable: a Stalker `create_link` ends the portal's previous
     * session, and the link it returns is single-use. So the first rung of a tune reuses what was
     * already minted, and a later rung mints again because the one before it is spent.
     */
    private suspend fun urlFor(
        channel: ChannelEntity,
        source: SourceEntity?,
        resolved: String?,
    ): String? = when {
        resolved != null -> resolved
        streamUrlResolver.needsResolve(source) ->
            runCatching { streamUrlResolver.resolve(source!!, channel.streamUrl) }.getOrNull()
        else -> channel.streamUrl
    }

    /**
     * Which of the channel's two addresses ExoPlayer should ask for.
     *
     * The `.m3u8` and the `.ts` of one channel are different muxes, and an engine that chokes on one
     * can play the other — so they are separate rungs rather than one attempt. This serves the plain
     * stream when the ladder is on the TS rung, or when ExoPlayer has already learned this session
     * that the channel has no working HLS variant.
     */
    private fun exoTuneUrl(channel: ChannelEntity, source: SourceEntity?): String =
        if (forceTsForExo == channel.streamUrl ||
            tv.own.owntv.player.LiveStreamQuirks.lacksHlsVariant(channel.streamUrl)
        ) {
            channel.streamUrl
        } else {
            channel.playStreamUrl(source)
        }

    /** Put [channel] on ExoPlayer, releasing mpv first when mpv currently holds the stream. mpv's stop
     *  is asynchronous, so handing over too early makes the app its own competitor for a session. */
    private suspend fun switchToExo(channel: ChannelEntity, source: SourceEntity?) {
        if (!_liveOnExo.value) {
            player.stopAndAwaitRelease()
            kotlinx.coroutines.delay(OwnTVPlayer.SURFACE_HANDOFF_MS)
            if (loadedId != channel.id) return
        }
        startOnExo(channel, source)
    }

    /**
     * Open [channel] on ExoPlayer, and watch it.
     *
     * The watch is the point. An engine that reports what happened is not the same as one that knows
     * when to give up, and every rung of [LiveExoWatchdog] exists because a real channel failed in a
     * way nothing else caught — a picture that never arrives while the audio plays, segment URLs the
     * provider refuses, a stream that opens and then delivers nothing, no decodable audio, or a
     * channel that played and then froze. Each of those hands the channel to mpv, which frequently
     * plays it.
     */
    private suspend fun startOnExo(
        channel: ChannelEntity,
        /** The playlist, for its User-Agent and its three per-playlist overrides. */
        source: SourceEntity?,
        /** A URL already minted for this tune — see [urlFor]. */
        resolved: String? = null,
    ) {
        val url = if (streamUrlResolver.needsResolve(source)) {
            urlFor(channel, source, resolved) ?: return
        } else {
            exoTuneUrl(channel, source)
        }
        exoWatchJob?.cancel()
        mpvOutcomeJob?.cancel() // ExoPlayer owns the channel now
        _liveOnExo.value = true
        // Same ordering as the reverse direction above: mpv lets go of the connection and the decoder
        // before ExoPlayer claims either.
        player.stop()
        exo.play(
            url,
            muted = false,
            meta = MediaMeta(
                title = channel.name,
                logoUrl = channel.displayLogoUrl,
                contentKey = pinKeyFor(channel),
            ),
            userAgent = source?.userAgent,
            prerollSecsOverride = prerollFor(source),
            liveBufferOverride = liveBufferFor(source),
            httpHeaders = channel.httpHeaders,
            drmConfig = channel.drmConfig,
        )
        publishToSystem()
        exoWatchJob = scope.launch {
            LiveExoWatchdog(
                engine = exo,
                // A watchdog outlives the tune that armed it — the user zaps, backs out, or starts a
                // film — and firing after that would stop a stream nobody complained about.
                stillOurs = { isStillExo(channel) },
                // Into the ladder rather than straight to mpv. The ladder decides what "next" means:
                // this engine's other stream format, the other engine, or nothing left at all — and
                // it is what stops a handover in each direction from bouncing the channel for ever.
                handOver = { reason -> advanceLadder(channel, source, reason) },
                onOpened = { ladderOpened() },
                // A provider back-off is a wait OwnTV agreed to, so it must not be charged to the
                // tune's budget — otherwise a perfectly good channel is abandoned over a delay the
                // app itself accepted.
                postponeDeadline = { ladder.postponeDeadline(it) },
                log = { engineLog(it) },
            ).watch(channel.name)
        }
    }

    /**
     * The HUD's engine button on a live channel: flip this channel between ExoPlayer and mpv, and
     * remember the choice for next time.
     *
     * The television calls the mpv side "compatibility mode" and files it per channel, and this is
     * that same store and that same pin — so a channel pinned on one device opens on mpv on the other
     * once the two have synced.
     */
    fun toggleLiveEngine() {
        val channel = _channel.value ?: return
        // A replay is a recorded programme, not the live stream: re-tuning here would swap what the
        // user is watching for whatever is on that channel now.
        if (_replaying.value || timeshift.isRewound) return
        // A protected channel has only one engine that can obtain its key, so swapping would trade a
        // playing channel for a guaranteed failure.
        if (channel.drmConfig != null) return
        scope.launch {
            val goToMpv = _liveOnExo.value
            forceMpvStore.pin(pinKeyFor(channel), goToMpv)
            val source = withContext(Dispatchers.IO) { sourceDao.getById(channel.sourceId) }
            engineLog("engine toggle '${channel.name}' -> ${if (goToMpv) "mpv" else "exoplayer"}")
            // A manual choice restarts the ladder around that engine, and for THIS tune only as an
            // "only" mode. This is the button the user has just pressed, on the engine they just
            // named, while watching: bouncing them off it seconds later makes the control look broken
            // and leaves them no way to stay put. The next tune of the same channel reads the pin and
            // gets the full ladder back, so a channel the chosen engine genuinely cannot play still
            // ends up somewhere that plays it rather than stuck for ever on one bad decision.
            armLadder(channel, source, tv.own.owntv.core.player.EnginePreference.onlyOn(goToMpv))
            if (goToMpv) startOnMpv(channel, source) else switchToExo(channel, source)
        }
    }

    // --- The fallback ladder ----------------------------------------------------------------------
    //
    // The phone had half of one: ExoPlayer was watched and its failures went to mpv, and mpv was a
    // terminus. So "mpv first" — which Phase 4 made a real setting rather than an inert one — had no
    // way back, a channel pinned to compatibility mode that mpv could not open simply sat there, and
    // neither engine ever retried a channel on its other stream format. This is the television's
    // ladder, and it is the television's because the ordering IS the feature: `LiveLadder` lives in
    // `:player-core` and is unit-tested there. Everything with a side effect stays here.

    /** The ladder for the tune on screen: which engine/format rungs are left, and how long is left. */
    private val ladder = LiveLadder()

    /** Settings → "Give up after", as a whole-tune budget. The phone displayed this slider and read it
     *  nowhere else — the budget belongs to the ladder, which the phone did not have. */
    private val ladderBudgetMs: StateFlow<Long> = settings.liveTuneTimeoutSecs
        .map { secs -> if (secs <= 0) LiveLadder.NO_BUDGET else secs * 1000L }
        .stateIn(scope, SharingStarted.Eagerly, LiveLadder.NO_BUDGET)

    /**
     * The channel whose ExoPlayer rung is the explicit `.ts` one.
     *
     * Keyed by channel rather than a bare flag, for the reason the television records: the URL helper
     * is used by more than the ladder, so a global flag left set by one channel's TS rung would send
     * every other channel to `.ts` until the next tune.
     */
    private var forceTsForExo: String? = null

    /** Engine routing goes to logcat unconditionally and to the diagnostics ring, so a release build
     *  can be read with `adb logcat -s LiveEngine`. Channel names only — never a stream URL. */
    private fun engineLog(message: String) {
        android.util.Log.i(ENGINE_TAG, message)
        tv.own.owntv.player.LiveDiagnosticsLog.event("engine: $message")
    }

    /**
     * Mirror a ladder decision into the user-visible playback error log.
     *
     * Called before the engine actually switches, so `_liveOnExo` still names the engine that failed.
     * At most four per tune and only ever on a failure, which keeps it inside the "these stay rare"
     * rule that log is built on.
     */
    private fun recordLadderEvent(
        event: tv.own.owntv.player.PlayerFailureReason,
        channel: ChannelEntity,
        detail: String,
    ) {
        tv.own.owntv.player.PlaybackErrorLog.event(
            context = context,
            engine = if (_liveOnExo.value) "ExoPlayer" else "mpv",
            live = true,
            reason = event,
            detail = "'${channel.name}': $detail",
        )
    }

    /**
     * Whether this channel's panel has already been caught handing out signed segment URLs it then
     * refuses.
     *
     * The lesson is panel-wide and lasts only for the session, so the first channel still pays the
     * detection and a provider that fixes its panel is back on the ExoPlayer-first path after the
     * next app start. Stalker sources are excluded: their stored address is a portal command, not a
     * URL, so there is no host to key on until it has been resolved — a network call this decision
     * must not make.
     */
    private fun panelRefusesSegments(channel: ChannelEntity, source: SourceEntity?): Boolean =
        !streamUrlResolver.needsResolve(source) &&
            tv.own.owntv.player.LiveStreamQuirks.refusesSegments(channel.playStreamUrl(source))

    /** Whether "Prefer HLS" actually rewrites this channel's URL — if it does not, an HLS rung and a
     *  TS rung are the same attempt and the ladder drops the HLS ones. A Stalker cmd is not a URL. */
    private fun hasHlsAlternative(channel: ChannelEntity, source: SourceEntity?): Boolean =
        !streamUrlResolver.needsResolve(source) && channel.playStreamUrl(source) != channel.streamUrl

    /** Reset the ladder for a fresh tune. Rungs climbed by the last tune are forgotten — a new tune is
     *  a new chance, including for a channel that ended the last one on its final rung. */
    private suspend fun armLadder(
        channel: ChannelEntity,
        source: SourceEntity?,
        preference: tv.own.owntv.core.player.EnginePreference,
    ) {
        forceTsForExo = null
        ladder.arm(
            channel.streamUrl,
            preference,
            budgetMs = ladderBudgetMs.value,
            nowMs = android.os.SystemClock.elapsedRealtime(),
        ) { hasHlsAlternative(channel, source) }
        startLadderDeadline(channel)
    }

    private var ladderDeadlineJob: Job? = null

    /**
     * The alarm behind "Give up after", so the budget bounds the black screen rather than only the
     * decision to climb another rung.
     *
     * Checking at rung boundaries alone is not enough: a rung entered at 24 s with a 35 s timeout of
     * its own runs to 59 s before anyone asks the time. The deadline is re-read on each pass rather
     * than captured, so a provider back-off that bought the tune more time moves this alarm with it.
     * A channel that opens cancels the alarm outright — a stream that plays and later stalls belongs
     * to the watchdogs, which are about recovery rather than about opening.
     */
    private fun startLadderDeadline(channel: ChannelEntity) {
        ladderDeadlineJob?.cancel()
        ladderDeadlineJob = scope.launch {
            while (ladder.owns(channel.streamUrl)) {
                val left = (ladder.deadlineAt() ?: return@launch) - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) break
                kotlinx.coroutines.delay(left)
            }
            // Both gates matter. The ladder can still own a channel the user has walked away from, and
            // this alarm stops an engine — on the mpv branch that is the shared full player, which by
            // then may be showing a film.
            if (!ladder.owns(channel.streamUrl)) return@launch
            if (!isStillExo(channel) && !isStillMpv(channel)) return@launch
            val detail = "no picture within ${ladderBudgetMs.value / 1000}s of tuning"
            engineLog("'${channel.name}' — giving up: $detail")
            recordLadderEvent(tv.own.owntv.player.PlayerFailureReason.LIVE_NO_FALLBACK, channel, detail)
            exoWatchJob?.cancel()
            mpvOutcomeJob?.cancel()
            mpvHandoffJob?.cancel()
            abandonTune(channel, detail)
        }
    }

    /** A picture arrived, so the opening budget has been met: stand the alarm down. */
    private fun ladderOpened() {
        ladderDeadlineJob?.cancel()
        ladderDeadlineJob = null
    }

    /**
     * The ladder has nothing left — it ran out of rungs or it ran out of time. Put the failure on
     * screen.
     *
     * Without this the spinner simply stays up: a stream that opens its playlist and then delivers no
     * segment produces no frame AND no error, so the honest answer has to be written by whoever
     * decided to stop trying. Whichever engine is showing is the one told to give up, because that is
     * the one the HUD is reading.
     */
    private fun abandonTune(channel: ChannelEntity, detail: String) {
        val reason = "'${channel.name}': $detail"
        if (_liveOnExo.value) exo.abandon(reason) else player.abandonLive(reason)
    }

    /**
     * Whether [reason] is the panel refusing the *request* rather than the stream failing — an
     * account-busy 458, a 403, a rate limit.
     *
     * Such a refusal must not teach the ladder anything: it is not a property of the channel, the
     * format or the engine, it is the account being busy, and it clears on its own.
     */
    private fun isRequestRefusal(reason: String): Boolean =
        tv.own.owntv.player.PlayerErrors.httpStatusIn(reason)
            ?.let { tv.own.owntv.player.LiveStreamQuirks.isRequestRefusal(it) } == true

    /** Still this channel, still on ExoPlayer — the guard every ExoPlayer-side callback needs. */
    private fun isStillExo(channel: ChannelEntity): Boolean =
        _liveOnExo.value && loadedId == channel.id

    /** Still this channel, still on mpv. */
    private fun isStillMpv(channel: ChannelEntity): Boolean =
        !_liveOnExo.value && loadedId == channel.id

    /** The in-flight handoff to mpv, so a newer rung supersedes an older one. */
    private var mpvHandoffJob: Job? = null

    private var mpvOutcomeJob: Job? = null

    /**
     * Move to the next untried rung after a failure, or give up when there is nothing left.
     *
     * This is the only place the ladder is climbed, from either engine's watcher, which is what makes
     * "each rung at most once" hold — and that finiteness is the safety property. Without it an
     * ExoPlayer failure handing over to mpv and an mpv failure handing back would bounce a channel
     * between the two for ever.
     */
    private suspend fun advanceLadder(channel: ChannelEntity, source: SourceEntity?, reason: String) {
        if (!ladder.owns(channel.streamUrl)) return // a newer tune owns the ladder now
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val outOfTime = ladder.expired(nowMs)
        val next = ladder.advance(failureWasAboutFormat = !isRequestRefusal(reason), nowMs = nowMs) ?: run {
            val detail = if (outOfTime) "$reason — gave up after ${ladderBudgetMs.value / 1000}s" else reason
            engineLog("'${channel.name}' — no fallback left ($detail)")
            recordLadderEvent(tv.own.owntv.player.PlayerFailureReason.LIVE_NO_FALLBACK, channel, detail)
            abandonTune(channel, detail)
            return
        }
        val label = ladder.label(next)
        engineLog("'${channel.name}' falling back to $label ($reason)")
        recordLadderEvent(tv.own.owntv.player.PlayerFailureReason.LIVE_FALLBACK, channel, "$label — $reason")
        if (next.onMpv) {
            // Detached on purpose, exactly as on the television: every automatic rung is dispatched
            // from inside a watcher job, and the handoff cancels those watchers the moment it takes
            // over — so run inline it would cancel itself at its first suspension point and leave a
            // permanent black screen with mpv never asked to load anything.
            mpvHandoffJob?.cancel()
            mpvHandoffJob = scope.launch { startOnMpv(channel, source, forceTs = !next.isHls) }
        } else {
            forceTsForExo = if (next.isHls) null else channel.streamUrl
            switchToExo(channel, source)
        }
    }

    /**
     * Watch a channel mpv has just been given, and take it to the next rung if mpv cannot play it.
     *
     * **"Opened" is a decoded picture or the spinner clearing — never `isPlaying`.** mpv seeds that
     * flag true at load time, so it says nothing about whether the stream ever arrived; a watcher
     * built on it would call every dead channel a success. The deadline is looser than ExoPlayer's
     * because mpv runs its own retry and format ladder internally first.
     */
    private fun watchMpvOutcome(channel: ChannelEntity, source: SourceEntity?) {
        mpvOutcomeJob?.cancel()
        mpvOutcomeJob = scope.launch {
            val failure = kotlinx.coroutines.withTimeoutOrNull(MPV_OPEN_TIMEOUT_MS) {
                combine(player.videoRes, player.buffering, player.error) { res, buffering, error ->
                    when {
                        error != null -> false to error.toString() // mpv gave up
                        res != null || !buffering -> true to null   // a picture, or the spinner cleared
                        else -> null                               // still trying
                    }
                }.first { it != null }
            }
            if (!isStillMpv(channel)) return@launch
            val reason = when {
                failure == null -> "mpv never opened it (${MPV_OPEN_TIMEOUT_MS / 1000}s, no picture and no error)"
                failure.first -> { engineLog("'${channel.name}' opened on mpv"); ladderOpened(); return@launch }
                else -> "mpv couldn't play it: ${failure.second}"
            }
            advanceLadder(channel, source, reason)
        }
    }

    /**
     * Give live playback back to mpv, because something that is not the live stream is about to open
     * on it: a catch-up programme, or a rewind into the provider's archive.
     *
     * **Without this the two engines played at once.** An archive always opens on mpv, but nothing
     * told ExoPlayer to let go, so the live stream carried on underneath: two sounds, two of the
     * playlist's connections spent, and the picture still ExoPlayer's — the archive was audible and
     * invisible. The television has done this since live moved to ExoPlayer there; it is its
     * `clearLiveOnExo()`, and the phone simply never gained it.
     *
     * The watchdog goes with it: it was armed for a live tune that is over, and firing afterwards
     * would hand a channel over while a recording of last night plays.
     */
    private fun handArchiveToMpv() {
        exoWatchJob?.cancel()
        // The ladder and its alarm belong to a LIVE tune. An archive is a different stream with its
        // own end, so leaving them armed would let the give-up alarm stop a replay that is playing
        // perfectly well, and let a stale rung hand the channel to the other engine underneath it.
        ladderDeadlineJob?.cancel()
        mpvOutcomeJob?.cancel()
        mpvHandoffJob?.cancel()
        _liveOnExo.value = false
        exo.stop()
    }

    /** Tag for the engine decisions above, so a support log can be filtered to just them. */
    private val ENGINE_TAG = "LiveEngine"

    // --- Multiview: channels kept from the browse screen ------------------------------------------
    // The plan's second entry point: pick two to four channels from the Live list, then play one and
    // the grid opens already filled. Held here, not in a view model, for the same reason everything
    // else here is: the list that fills it and the player that empties it are different screens.
    private val _multiviewSelection = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val multiviewSelection: StateFlow<List<ChannelEntity>> = _multiviewSelection

    /** Keep [channel] for the grid, up to [limit] tiles. Adding one twice does nothing. */
    fun addToMultiview(channel: ChannelEntity, limit: Int) {
        val current = _multiviewSelection.value
        if (current.any { it.id == channel.id } || current.size >= limit) return
        _multiviewSelection.value = current + channel
    }

    fun clearMultiviewSelection() {
        _multiviewSelection.value = emptyList()
    }

    /** The playlist a channel came from, so a caller can ask what it allows (the tile budget). */
    suspend fun sourceOf(channel: ChannelEntity): tv.own.owntv.core.database.entity.SourceEntity? =
        withContext(Dispatchers.IO) { sourceDao.getById(channel.sourceId) }

    /**
     * Tune [channel] into a Multiview tile's own engine.
     *
     * Routed through here for the same reason the television routes it through its view model: which
     * URL a channel actually plays is the playlist's business — its User-Agent, the channel's headers,
     * and, for a Stalker portal, a command that has to be resolved to a link per play. None of that
     * belongs in a grid, and a second copy of it would drift.
     *
     * The system session is deliberately **not** published: the lockscreen, the audio focus and the
     * foreground service belong to the one stream the user is watching, and a grid of four muted
     * pictures is not four of those.
     */
    fun tuneTile(engine: tv.own.owntv.player.LivePreviewEngine, channel: ChannelEntity, muted: Boolean) {
        scope.launch {
            if (!dataSaver.allowsStreaming()) return@launch
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            if (!AdultCategoryClassifier.allows(pid, channel.categoryId, profileDao, categoryDao)) return@launch
            val source = withContext(Dispatchers.IO) { sourceDao.getById(channel.sourceId) }
            val url = if (streamUrlResolver.needsResolve(source)) {
                runCatching { streamUrlResolver.resolve(source!!, channel.streamUrl) }.getOrNull() ?: return@launch
            } else {
                channel.streamUrl
            }
            engine.play(
                url,
                muted = muted,
                meta = tv.own.owntv.player.MediaMeta(title = channel.name, logoUrl = channel.displayLogoUrl),
                userAgent = source?.userAgent,
                // A tile is one of this playlist's streams like any other, so the playlist's own
                // pre-buffer and latency apply to it — which is what the television does too.
                prerollSecsOverride = prerollFor(source),
                liveBufferOverride = liveBufferFor(source),
                httpHeaders = channel.httpHeaders,
                drmConfig = channel.drmConfig,
            )
        }
    }

    /**
     * Start this channel without a picture when the user has already said so — either for this
     * channel in particular, or for mobile data in general.
     *
     * Read once, after the stream opens: dropping the video track is something the engine does to a
     * stream it already has, and asking before there is one would have nothing to act on.
     */
    private suspend fun applyAudioOnlyDefault(channel: ChannelEntity) {
        val remembered = settings.audioPerChannelNow() && audioOnlyStore.isAudioOnly(audioOnlyKey(channel))
        val onData = settings.audioOnMobileDataNow() && dataSaver.isMetered()
        // Both ways, every time. Turning the picture off is a decision about *this* channel, and the
        // engine keeps the flag across a retune — so without the else, one tap on Sound only silently
        // became every channel afterwards, looking for all the world like a setting that remembered.
        // Asked of the engine that actually has the stream. Sent to mpv, none of this happened on a
        // live channel: a channel the user had put into sound-only opened with its picture again,
        // and "Sound only on mobile data" quietly did nothing at all.
        val playing = currentEngine
        if (remembered || onData) playing.enterAudioOnly() else playing.exitAudioOnly()
    }

    /**
     * Turn the picture off or back on, and remember the choice for this channel when the user asked
     * for it to be remembered. The player alone would forget it the moment the channel changed.
     */
    fun setAudioOnly(audioOnly: Boolean) {
        // The engine holding the stream, not mpv: pressing Sound only on a live channel left the
        // screen but went on decoding video nobody could see — the opposite of what the button is
        // for, and the phone's largest single battery and data saving.
        val playing = currentEngine
        if (audioOnly) playing.enterAudioOnly() else playing.exitAudioOnly()
        val channel = _channel.value ?: return
        scope.launch {
            if (settings.audioPerChannelNow()) audioOnlyStore.set(audioOnlyKey(channel), audioOnly)
        }
    }

    /** The stable per-item key, with the stream URL as the fallback the engine stores also use. */
    private fun audioOnlyKey(channel: ChannelEntity): String =
        enginePinKey(channel.sourceId, MediaType.LIVE.name, channel.remoteId) ?: channel.streamUrl

    /**
     * Every Live TV category across every playlist, for the Multiview picker.
     *
     * The picker cannot start at a channel list the way the player's does: the player already has a
     * channel playing and its category is the obvious place to look, while an empty tile has no such
     * context — and a flat list of every channel is tens of thousands of rows on a real playlist.
     * Hidden categories are dropped and renames applied, so it matches what is seen everywhere else.
     */
    suspend fun liveCategoriesForPicker(): List<Pair<Long, String>> {
        val c = ctx.value
        if (c.profileId < 0) return emptyList()
        val cust = custom.value
        return withContext(Dispatchers.IO) {
            categoryDao.observe(c.liveSourceIds.ifEmpty { listOf(-1L) }, MediaType.LIVE).first()
        }
            .filter { CustomizeKeys.category(it) !in cust.hiddenItems }
            .map { cat -> cat.id to (cust.itemNames[CustomizeKeys.category(cat)] ?: cat.name) }
    }

    /** One category's channels, with the same hide/rename treatment the rest of the app applies. */
    suspend fun channelsInCategoryForPicker(categoryId: Long): List<ChannelEntity> {
        val c = ctx.value
        if (c.profileId < 0) return emptyList()
        val category = withContext(Dispatchers.IO) { categoryDao.getById(categoryId) } ?: return emptyList()
        val cust = custom.value
        return withContext(Dispatchers.IO) {
            channelDao.snapshotByCategoryManual(
                categoryId = category.id,
                profileId = c.profileId,
                contextKey = CustomizeKeys.category(category),
                limit = SIBLING_LIMIT,
            )
        }
            .filter { CustomizeKeys.channel(it) !in cust.hiddenItems }
            .map { ch -> cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch }
    }

    private suspend fun loadSiblings(channel: ChannelEntity) {
        val c = ctx.value
        if (c.profileId < 0) return
        val category = channel.categoryId?.let { withContext(Dispatchers.IO) { categoryDao.getById(it) } }
        val list = withContext(Dispatchers.IO) {
            if (category != null) {
                channelDao.snapshotByCategoryManual(
                    categoryId = category.id,
                    profileId = c.profileId,
                    contextKey = CustomizeKeys.category(category),
                    limit = SIBLING_LIMIT,
                )
            } else {
                // No category of its own: fall back to the profile's whole list, in provider order.
                channelDao.snapshotAll(c.liveSourceIds.ifEmpty { listOf(-1L) }, SIBLING_LIMIT)
            }
        }
        val cust = custom.value
        _siblings.value = list
            .filter { CustomizeKeys.channel(it) !in cust.hiddenItems }
            .map { ch -> cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch }
    }

    /** Already-aired programmes this channel's archive still holds, newest first. */
    suspend fun catchupProgrammes(): List<EpgProgrammeEntity> {
        val channel = _channel.value ?: return emptyList()
        return epgReader.catchupProgrammes(
            channel,
            custom.value,
            settings.epgOffsetMinutes.first(),
            ctx.value.sourceIds,
        )
    }

    /**
     * Replay a past programme from the archive. Seekable, so it plays as VOD rather than as live.
     *
     * [on] is the channel it aired on, for the Guide, where a programme is picked without tuning its
     * channel first — starting the live stream only to abandon it a second later would cost the user
     * a connection and the provider a session. Omitted, it is the channel already playing.
     */
    fun playCatchup(programme: EpgProgrammeEntity, on: ChannelEntity? = null) {
        val channel = on ?: _channel.value ?: return
        if (channel.id != loadedId) {
            loadedId = channel.id
            _channel.value = channel
            _nowNext.value = null
            _timelineProgrammes.value = emptyList()
        }
        scope.launch {
            if (!dataSaver.allowsStreaming()) return@launch
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            if (!AdultCategoryClassifier.allows(pid, channel.categoryId, profileDao, categoryDao)) return@launch
            val url = archiveUrls.forProgramme(channel, programme) ?: run {
                // Silence here is indistinguishable from a broken button: the tap did nothing, said
                // nothing, and left the user to guess whether the app or the provider was at fault.
                catchupUnavailable()
                return@launch
            }
            val source = withContext(Dispatchers.IO) { sourceDao.getById(channel.sourceId) }
            // isArchive: providers cut archive segments mid-GOP, and the engine needs to tolerate it.
            _replaying.value = true
            lastCatchup = programme
            val handedOver = cast.offer(
                this@LiveTuner,
                CastRequest(
                    url = url,
                    title = channel.name,
                    subtitle = programme.title,
                    logoUrl = channel.displayLogoUrl,
                    isLive = false,
                    httpHeaders = channel.httpHeaders,
                ),
            )
            if (!handedOver) {
                // A replay is an mpv stream, so the live ExoPlayer engine lets go of its channel —
                // its connection and its decoder — before mpv asks for either.
                handArchiveToMpv()
                player.play(
                    url = url,
                    title = channel.name,
                    subtitle = programme.title,
                    logoUrl = channel.displayLogoUrl,
                    isLive = false,
                    isArchive = true,
                    userAgent = source?.userAgent,
                    httpHeaders = channel.httpHeaders,
                )
                publishToSystem()
            }
            recordHistory(pid, channel.id)
            // The clock over a replay says yesterday 13:00, not now — same as on the television.
            // Not while casting: the timeshift follows the LOCAL player's position, and there is no
            // local player to follow.
            if (!handedOver) timeshift.followArchiveFrom(programme.startMs)
        }
    }

    /**
     * "Go back to…": start the archive [offsetSec] seconds behind live in one jump.
     *
     * Not a replay — this is still the channel, just behind, so the live bar and the way back to now
     * stay. That is why it goes through the timeshift rather than through [playCatchup].
     */
    fun jumpBackTo(offsetSec: Int) {
        if (casting()) return
        val ch = _channel.value?.takeIf { it.catchup } ?: return
        _replaying.value = false
        timeshift.beginAt(ch, offsetSec)
    }

    /**
     * Tell the user the archive could not be opened.
     *
     * Every catch-up path here returned quietly when no URL could be built, so a provider without a
     * recording and a bug in the app looked identical from the sofa. The television says the same
     * sentence through its own in-app toast; this is the phone's half of that.
     */
    private fun catchupUnavailable() {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.content_epg_catchup_unavailable),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /** Offsets worth offering in the catch-up sheet, nearest first; empty without an archive. */
    fun jumpOptions(): List<Int> =
        if (casting()) emptyList() else _channel.value?.let { timeshift.jumpOptions(it) } ?: emptyList()

    /**
     * Tune the channel carrying provider number [number] — the numeric entry in the channel sheet.
     *
     * The current playlist's own channels first, then the profile's other Live playlists, because a
     * number is the provider's and two providers routinely disagree about who is channel 101. Two
     * visible channels with the same number and no way to choose between them is [DirectTune.Ambiguous]
     * rather than a silent guess.
     */
    suspend fun tuneByNumber(number: Int): DirectTune {
        val sourceIds = ctx.value.liveSourceIds.ifEmpty { return DirectTune.NotFound }
        val playing = _channel.value
        return runCatching {
            val hidden = custom.value.hiddenItems
            val ordered = if (playing == null) sourceIds else {
                listOf(playing.sourceId) + sourceIds.filter { it != playing.sourceId }
            }
            // Stage by source, so the playing playlist's own 101 wins over another playlist's 101
            // instead of the two of them cancelling each other out as an ambiguity.
            for (sourceId in ordered) {
                val hits = withContext(Dispatchers.IO) { channelDao.findByNumber(listOf(sourceId), number) }
                    .filter { CustomizeKeys.channel(it) !in hidden }
                when (hits.size) {
                    0 -> continue
                    1 -> {
                        switchTo(hits.first())
                        return DirectTune.Found(hits.first().name)
                    }
                    else -> return DirectTune.Ambiguous(hits.size)
                }
            }
            DirectTune.NotFound
        }.getOrElse { DirectTune.Failed }
    }

    /** Drag back into the archive (+) or toward live (−), in seconds. */
    fun scrubLive(deltaSec: Int) {
        if (casting()) return
        val ch = _channel.value ?: return
        timeshift.scrub(ch, deltaSec)
    }

    /**
     * How deep this channel's archive goes, in seconds — the length of the rewind bar.
     *
     * **Zero while casting**, which is what takes the rewind bar off the screen. Live rewind works by
     * loading one archive URL after another and watching the local player's position to know when to
     * load the next; on a receiver there is no such position, so offering the bar would give the user
     * a control that quietly does nothing.
     */
    fun archiveWindowSec(): Int =
        if (casting()) 0 else _channel.value?.let { timeshift.windowSec(it) } ?: 0

    /** Whether the stream is on a receiver rather than on this phone. */
    private fun casting(): Boolean = cast.engine.value != null

    /** Back to the real-time edge, off the archive stream. */
    fun goToLive() {
        timeshift.clear()
        _channel.value?.let { ch -> scope.launch { start(ch) } }
    }

    /**
     * Turn one point in the archive into a playing stream — the "URL out" half of [LiveTimeshift].
     * False when no archive URL can be built, or the user reached live while it was being resolved.
     */
    private suspend fun loadArchiveStream(ch: ChannelEntity, startMs: Long, offsetSec: Int): Boolean {
        val tz = withContext(Dispatchers.IO) { settings.resolveCatchupTimeZone() }
        val (url, sourceUa) = withContext(Dispatchers.IO) {
            val source = sourceDao.getById(ch.sourceId) ?: return@withContext null
            archiveUrls.forTimeshift(ch, source, startMs, offsetSec, tz)?.let { it to source.userAgent }
        } ?: run {
            // The timeshift hands back to the live edge from here, which on its own is indistinguishable
            // from "Go back to…" doing nothing at all.
            catchupUnavailable()
            return false
        }
        if (timeshift.offsetSec.value == null) return false // user jumped back to live meanwhile
        // The rewind plays out of the archive on mpv, so the live ExoPlayer engine stops first. This
        // is the one that cost two connections and made two sounds: drag the bar back ten minutes and
        // the live stream went on playing behind the archive.
        handArchiveToMpv()
        player.play(
            url = url,
            title = ch.name,
            logoUrl = ch.displayLogoUrl,
            isArchive = true,
            userAgent = sourceUa,
            httpHeaders = ch.httpHeaders,
            rewindStartMs = startMs,
        )
        publishToSystem()
        return true
    }

    /** Stop playing altogether — the mini player's swipe-down, and nothing else. */
    fun stop() {
        timeshift.clear()
        loadedId = null
        lastCatchup = null
        _channel.value = null
        _nowNext.value = null
        _timelineProgrammes.value = emptyList()
        // Withdraw first: a session left published after the sound stops keeps answering the
        // lockscreen and the headphone button for a stream that no longer exists.
        cast.release(this)
        session.attach(null)
        PlaybackService.stop(context)
        // Every watcher the ladder owns, or one of them fires on a channel nobody is watching and
        // stops the shared player — which by then may be showing a film.
        exoWatchJob?.cancel()
        ladderDeadlineJob?.cancel()
        mpvOutcomeJob?.cancel()
        mpvHandoffJob?.cancel()
        _liveOnExo.value = false
        exo.stop()
        player.stop()
    }

    /**
     * The television is taking the channel. Live has no position worth carrying — the receiver joins
     * at the edge, which is where the phone was too — so the number is only there for the interface.
     */
    override fun releaseToCast(): Long {
        timeshift.clear()
        val position = currentEngine.position.value
        session.attach(null)
        // BOTH engines. Tapping Cast while a live channel played stopped mpv only, so ExoPlayer went
        // on playing the same channel on the handset beside the television — two pictures, two
        // sounds, and two of the playlist's connections for one thing being watched.
        //
        // The ladder goes with them: the channel is the television's now, and a give-up alarm or an
        // mpv watcher left armed would act on a stream that is playing perfectly well in another room.
        exoWatchJob?.cancel()
        ladderDeadlineJob?.cancel()
        mpvOutcomeJob?.cancel()
        mpvHandoffJob?.cancel()
        _liveOnExo.value = false
        exo.stop()
        player.stop()
        return position
    }

    /**
     * The cast ended, so the channel comes back here. Restarted rather than resumed: a live stream
     * has no position to return to, and re-tuning is what puts the phone back at the edge.
     */
    override fun resumeFromCast(positionMs: Long) {
        val channel = _channel.value ?: return
        // A replay is a programme, not a channel, so coming back to the live edge would be the wrong
        // thing entirely — it is started again instead. From its beginning: the receiver's position
        // is not a place the archive URL can be re-entered at.
        val replay = lastCatchup.takeIf { _replaying.value }
        if (replay != null) playCatchup(replay, channel) else scope.launch { start(channel) }
    }

    private suspend fun recordHistory(profileId: Long, channelId: Long) {
        runCatching {
            withContext(Dispatchers.IO) {
                historyDao.record(
                    WatchHistoryEntity(profileId = profileId, mediaType = MediaType.LIVE, itemId = channelId),
                )
            }
        }
    }

    private companion object {
        const val SIBLING_LIMIT = 2_000

        /**
         * How long mpv gets to produce a picture before the ladder moves on.
         *
         * The television's own figure, and deliberately looser than ExoPlayer's: mpv walks its own
         * internal retry and format ladder first, so cutting it short would abandon channels it was
         * about to open.
         */
        const val MPV_OPEN_TIMEOUT_MS = 35_000L
    }
}

/**
 * What typing a channel number produced. Every outcome is something the user is told: a number that
 * matches nothing must not look like a tap that was simply ignored.
 */
sealed interface DirectTune {
    data class Found(val name: String) : DirectTune
    data object NotFound : DirectTune
    data class Ambiguous(val count: Int) : DirectTune
    data object Failed : DirectTune
}
