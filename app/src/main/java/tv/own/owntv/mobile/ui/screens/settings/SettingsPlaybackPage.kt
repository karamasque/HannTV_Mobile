package tv.own.owntv.mobile.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.database.entity.FOLLOW_GLOBAL_LATENCY_SECS
import tv.own.owntv.core.database.entity.FOLLOW_GLOBAL_PREROLL
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.settings.LiveLatency
import tv.own.owntv.core.settings.SeekSteps
import tv.own.owntv.core.live.DEFAULT_MULTIVIEW_TILES
import tv.own.owntv.core.live.MAX_MULTIVIEW_TILES
import tv.own.owntv.core.live.MIN_MULTIVIEW_TILES
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SubtitleStyle
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.SettingRow
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.player.ZoomMode
import tv.own.owntv.mobile.ui.theme.glassDialogWindow

/** Which picker is open. One at a time, so one nullable holds them all. */
private enum class PlaybackSheet {
    LIVE_ENGINE, VOD_ENGINE, ZOOM, SURROUND, AUDIO_LANG, SUB_LANG,
    RESUME, LATENCY, SEEK_STEP, REWIND_STEP, EXTERNAL_PLAYER, MULTIVIEW_TILES,

    /**
     * The per-playlist overrides, two levels each: pick the playlist, then pick its value. The second
     * level goes back to the first rather than closing, because setting two playlists in a row is the
     * normal case and a full trip from the row for each one is not.
     */
    ENGINE_SOURCES, ENGINE_SOURCE, LATENCY_SOURCES, LATENCY_SOURCE, LATENCY_SOURCE_CUSTOM,
    PREROLL_SOURCES, PREROLL_SOURCE,
}

/** Which set of remembered per-item choices a confirmation is about to forget. */
private enum class ResetTarget(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    ENGINE_PINS(
        R.string.settings_reset_player_choices_confirm,
        R.string.settings_reset_player_choices_confirm_description,
    ),
    ZOOM(
        R.string.settings_reset_saved_zoom_confirm,
        R.string.settings_reset_saved_zoom_confirm_description,
    ),
    VOLUME(
        R.string.settings_reset_saved_volume_confirm,
        R.string.settings_reset_saved_volume_confirm_description,
    ),
    AUDIO_DELAY(
        R.string.settings_reset_saved_audio_delay_confirm,
        R.string.settings_reset_saved_audio_delay_confirm_description,
    ),
}

/**
 * The video player, in the television's own six sections: Engine & picture, Live TV, Sound,
 * Subtitles, Episodes, Diagnostics.
 *
 * These settings are core's and shared with the TV app — the same stored values under the same keys,
 * so a backup taken here restores onto a television and means the same thing, and a row pinned to
 * Quick on one app is the same row on the other. The settings a television has no use for are on the
 * Playback group page instead, under "Mobile".
 */
@Composable
fun SettingsVideoPlayerPage(
    onOpenLeaf: (SettingsLeaf) -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val s = vm.settings
    var sheet by remember { mutableStateOf<PlaybackSheet?>(null) }
    var resetting by remember { mutableStateOf<ResetTarget?>(null) }
    // Set while auto frame rate is waiting to be turned on against the warning below Android 12.
    var afrWarning by remember { mutableStateOf(false) }
    // Which playlist the second level of a per-playlist picker is editing.
    var overrideSource by remember { mutableStateOf<SourceEntity?>(null) }
    // What to run if the user accepts the low-latency warning, and what to run if they back out.
    var lowWarning by remember { mutableStateOf<Pair<() -> Unit, () -> Unit>?>(null) }

    val sources by vm.sources.collectAsStateWithLifecycle()
    val enginePins by vm.vodEnginePinCount.collectAsStateWithLifecycle()
    val savedZoom by vm.savedZoomCount.collectAsStateWithLifecycle()
    val savedVolume by vm.savedVolumeCount.collectAsStateWithLifecycle()
    val savedAudioDelay by vm.savedAudioDelayCount.collectAsStateWithLifecycle()

    val liveEngine = s.liveEnginePreference.pref(EnginePreference.EXO_ONLY)
    val vodEngine = s.vodEnginePreference.pref(EnginePreference.MPV_FIRST)
    val autoFrameRate = s.autoFrameRate.pref(false)
    val zoom = s.defaultZoom.pref(ZoomMode.FIT.name)
    val volume = s.defaultVolume.pref(100)
    val surround = s.surroundMode.pref(SurroundMode.AUTO)
    val audioLang = s.preferredAudioLang.pref("")
    val audioDelay = s.audioDelayMs.pref(0)
    val subLang = s.preferredSubLang.pref("")
    val latency = LiveLatency.fromName(s.liveLatencyMode.pref(LiveLatency.BALANCED.name))
    val latencySecs = s.liveLatencyCustomSecs.pref(LiveBuffer.CUSTOM_DEFAULT)
    val preroll = s.livePrerollSecs.pref(0)
    val tuneTimeout = s.liveTuneTimeoutSecs.pref(0)
    val seekStep = s.seekStepSec.pref(SeekSteps.DEFAULT_SEEK_STEP_SEC)
    val rewindStep = s.liveRewindStepSec.pref(SeekSteps.DEFAULT_LIVE_REWIND_STEP_SEC)
    // ASK, because that is what core stores when nothing has been chosen. Showing AUTO here named a
    // setting the app was not actually using, in the one frame before the real value arrives.
    val resume = s.resumeMode.pref(SettingsRepository.ResumeMode.ASK)
    val subStyleOn = s.subtitleStyleEnabled.pref(false)
    val externalLive = s.externalPlayerLive.pref(false)
    val externalMovies = s.externalPlayerMovies.pref(false)
    val externalSeries = s.externalPlayerSeries.pref(false)
    val externalOn = externalLive || externalMovies || externalSeries
    val multiviewEnabled = s.multiviewEnabled.pref(false)
    val multiviewTiles = s.multiviewTiles.pref(DEFAULT_MULTIVIEW_TILES)

    SettingsPage(modifier) {
        settingsSection(R.string.settings_vp_section_engine)
        settingsNote(R.string.settings_vp_section_engine_summary)
        settingsGroup(key = "live-engine") {
            SettingRow(
                title = stringResource(R.string.settings_live_tv_player),
                subtitle = stringResource(R.string.settings_live_player_description),
                value = engineLabel(liveEngine),
                onClick = { sheet = PlaybackSheet.LIVE_ENGINE },
            )
        }
        if (sources.isNotEmpty()) {
            settingsGroup(key = "live-engine-sources") {
                SettingRow(
                    title = stringResource(R.string.settings_live_engine_per_playlist),
                    subtitle = stringResource(R.string.settings_live_engine_per_playlist_description),
                    value = overrideCountLabel(sources.count { it.liveEnginePreference != null }),
                    onClick = { sheet = PlaybackSheet.ENGINE_SOURCES },
                )
            }
        }
        settingsGroup(key = "vod-engine") {
            SettingRow(
                title = stringResource(R.string.settings_movies_series_player),
                subtitle = stringResource(R.string.settings_movies_player_description),
                value = engineLabel(vodEngine),
                onClick = { sheet = PlaybackSheet.VOD_ENGINE },
            )

            SettingRow(
                title = stringResource(R.string.settings_reset_player_choices),
                subtitle = stringResource(R.string.settings_reset_player_choices_description),
                value = rememberedCountLabel(enginePins),
                enabled = enginePins > 0,
                onClick = { resetting = ResetTarget.ENGINE_PINS },
            )

            QuickSwitchRow(
                vm = vm,
                toggle = quickToggle("vp_hw"),
                subtitle = stringResource(R.string.settings_hardware_decoding_description),
            )

            QuickSwitchRow(
                vm = vm,
                toggle = quickToggle("vp_deinterlace"),
                subtitle = stringResource(R.string.settings_deinterlace_description),
            )

            QuickSwitchRow(
                vm = vm,
                toggle = quickToggle("vp_hdr"),
                subtitle = stringResource(R.string.settings_hdr_description),
            )

            SettingRow(
                title = stringResource(R.string.settings_auto_frame_rate),
                subtitle = stringResource(R.string.settings_auto_frame_rate_description),
                checked = autoFrameRate,
                // Below Android 12 there is no way to ask the display which refresh rates it can
                // reach without blanking it, so turning this ON asks first. Turning it off is
                // immediate — the same rule the television follows.
                onCheckedChange = { on ->
                    if (on && !afrSafe) afrWarning = true else vm.edit { setAutoFrameRate(on) }
                },
            )

            // Multiview. The phone had neither of these: the feature defaults to off and there was
            // no way to turn it on, so the grid was unreachable however well it worked.
            SettingRow(
                title = stringResource(R.string.settings_multiview),
                subtitle = stringResource(R.string.settings_multiview_description),
                checked = multiviewEnabled,
                onCheckedChange = { on -> vm.edit { setMultiviewEnabled(on) } },
            )

            if (multiviewEnabled) {
                SettingRow(
                    title = stringResource(R.string.settings_multiview_tiles_max),
                    subtitle = stringResource(R.string.settings_multiview_description),
                    // "Max 4", not "4": the number is a ceiling. The grid opens with two and grows
                    // only when the user asks, so a bare number would read as "every grid is this big".
                    value = stringResource(R.string.settings_multiview_tiles_max_value, multiviewTiles),
                    onClick = { sheet = PlaybackSheet.MULTIVIEW_TILES },
                )
            }

            SettingRow(
                title = stringResource(R.string.settings_external_player),
                subtitle = stringResource(R.string.settings_external_player_row_description),
                value = stringResource(if (externalOn) R.string.common_on else R.string.common_off),
                onClick = { sheet = PlaybackSheet.EXTERNAL_PLAYER },
            )

            SettingRow(
                title = stringResource(R.string.settings_default_zoom),
                subtitle = stringResource(R.string.settings_default_zoom_description),
                value = stringResource(zoomModeOf(zoom).labelRes),
                onClick = { sheet = PlaybackSheet.ZOOM },
            )

            SettingRow(
                title = stringResource(R.string.settings_reset_saved_zoom),
                subtitle = stringResource(R.string.settings_reset_saved_zoom_description),
                value = rememberedCountLabel(savedZoom),
                enabled = savedZoom > 0,
                onClick = { resetting = ResetTarget.ZOOM },
            )

            SettingRow(
                title = stringResource(R.string.settings_seek_step),
                subtitle = stringResource(R.string.settings_seek_step_description),
                value = stringResource(R.string.settings_live_buffer_seconds, seekStep),
                onClick = { sheet = PlaybackSheet.SEEK_STEP },
            )

            SettingRow(
                title = stringResource(R.string.settings_live_rewind_step),
                subtitle = stringResource(R.string.settings_live_rewind_step_description),
                value = stringResource(R.string.settings_live_buffer_seconds, rewindStep),
                onClick = { sheet = PlaybackSheet.REWIND_STEP },
            )
        }

        settingsSection(R.string.settings_live_tv)
        settingsNote(R.string.settings_vp_section_live_summary)
        settingsGroup(key = "latency") {
            SettingRow(
                title = stringResource(R.string.settings_live_latency),
                subtitle = stringResource(R.string.settings_live_latency_description),
                value = if (latency == LiveLatency.CUSTOM) {
                    stringResource(R.string.settings_live_buffer_seconds, latencySecs)
                } else {
                    stringResource(latency.labelRes())
                },
                onClick = { sheet = PlaybackSheet.LATENCY },
            )
        }
        if (latency == LiveLatency.CUSTOM) {
            settingsGroup(key = "latency-secs") {
                SettingsSlider(
                    title = stringResource(R.string.settings_live_latency_custom),
                    value = latencySecs,
                    range = LiveBuffer.CUSTOM_MIN..LiveBuffer.CUSTOM_MAX,
                    valueLabel = stringResource(R.string.settings_live_buffer_seconds, latencySecs),
                    // The acknowledgement is asked once, on the drag that crosses below Balanced.
                    // Asking at every step would make the slider unusable; never asking would let a
                    // buffer too small for the stream look like the app breaking rather than a choice.
                    onValueChange = { secs ->
                        vm.edit { setLiveLatencyCustomSecs(secs) }
                        if (LiveBuffer.isLowLatency(secs) && !LiveBuffer.isLowLatency(latencySecs)) {
                            lowWarning = Pair(
                                {},
                                { vm.edit { setLiveLatencyMode(LiveLatency.BALANCED.name) } },
                            )
                        }
                    },
                )
            }
        }
        if (sources.isNotEmpty()) {
            settingsGroup(key = "latency-sources") {
                SettingRow(
                    title = stringResource(R.string.settings_live_latency_per_playlist),
                    subtitle = stringResource(R.string.settings_live_latency_per_playlist_description),
                    value = overrideCountLabel(sources.count { it.liveLatencyMode != null }),
                    onClick = { sheet = PlaybackSheet.LATENCY_SOURCES },
                )
            }
        }
        settingsGroup(key = "preroll") {
            SettingsSlider(
                title = stringResource(R.string.settings_live_preroll),
                subtitle = stringResource(R.string.settings_live_preroll_description),
                value = preroll,
                range = 0..30,
                valueLabel = stringResource(R.string.settings_video_seconds, preroll),
                onValueChange = { secs -> vm.edit { setLivePrerollSecs(secs) } },
            )
        }
        if (sources.isNotEmpty()) {
            settingsGroup(key = "preroll-sources") {
                SettingRow(
                    title = stringResource(R.string.settings_live_preroll_per_playlist),
                    subtitle = stringResource(R.string.settings_live_preroll_per_playlist_description),
                    value = overrideCountLabel(
                        sources.count { it.livePrerollSecs != FOLLOW_GLOBAL_PREROLL },
                    ),
                    onClick = { sheet = PlaybackSheet.PREROLL_SOURCES },
                )
            }
        }
        settingsGroup(key = "tune-timeout") {
            SettingsSlider(
                title = stringResource(R.string.settings_live_tune_timeout),
                subtitle = stringResource(R.string.settings_live_tune_timeout_description),
                value = tuneTimeout,
                range = 0..60,
                valueLabel = stringResource(R.string.settings_live_buffer_seconds, tuneTimeout),
                onValueChange = { secs -> vm.edit { setLiveTuneTimeoutSecs(secs) } },
            )

            QuickSwitchRow(
                vm = vm,
                toggle = quickToggle("vp_channel_numbers"),
                subtitle = stringResource(R.string.settings_channel_numbers_description),
            )
        }

        settingsSection(R.string.settings_vp_section_sound)
        settingsNote(R.string.settings_vp_section_sound_summary)
        settingsGroup(key = "volume") {
            SettingsSlider(
                title = stringResource(R.string.settings_default_volume),
                subtitle = stringResource(R.string.settings_default_volume_description),
                value = volume,
                range = 0..150,
                onValueChange = { pct -> vm.edit { setDefaultVolume(pct) } },
            )

            SettingRow(
                title = stringResource(R.string.settings_reset_saved_volume),
                subtitle = stringResource(R.string.settings_reset_saved_volume_description),
                value = rememberedCountLabel(savedVolume),
                enabled = savedVolume > 0,
                onClick = { resetting = ResetTarget.VOLUME },
            )

            SettingRow(
                title = stringResource(R.string.settings_surround_sound),
                subtitle = stringResource(R.string.settings_surround_description),
                value = stringResource(surround.labelRes()),
                onClick = { sheet = PlaybackSheet.SURROUND },
            )

            SettingRow(
                title = stringResource(R.string.settings_preferred_audio_language),
                value = trackLanguageName(audioLang),
                onClick = { sheet = PlaybackSheet.AUDIO_LANG },
            )

            // 25 ms steps across ±5s: the offset being corrected is a device's picture-processing
            // delay, which lands in the tens of milliseconds — a coarser step could only bracket it.
            SettingsSlider(
                title = stringResource(R.string.settings_audio_sync),
                subtitle = stringResource(R.string.settings_audio_sync_description),
                value = audioDelay,
                range = -5000..5000,
                steps = 399,
                valueLabel = stringResource(R.string.settings_audio_delay_value, audioDelay),
                onValueChange = { ms -> vm.edit { setAudioDelayMs(ms / 25 * 25) } },
            )

            SettingRow(
                title = stringResource(R.string.settings_reset_saved_audio_delay),
                subtitle = stringResource(R.string.settings_reset_saved_audio_delay_description),
                value = rememberedCountLabel(savedAudioDelay),
                enabled = savedAudioDelay > 0,
                onClick = { resetting = ResetTarget.AUDIO_DELAY },
            )
        }

        settingsSection(R.string.settings_subtitles)
        settingsNote(R.string.settings_vp_section_subtitles_summary)
        settingsGroup(key = "sub-style") {
            SettingRow(
                title = stringResource(R.string.settings_subtitle_appearance),
                subtitle = stringResource(R.string.settings_subtitle_appearance_description),
                value = stringResource(if (subStyleOn) R.string.common_on else R.string.common_off),
                onClick = { onOpenLeaf(SettingsLeaf.SUBTITLE_APPEARANCE) },
            )

            SettingRow(
                title = stringResource(R.string.settings_preferred_subtitle_language),
                subtitle = stringResource(R.string.settings_preferred_language_description),
                value = trackLanguageName(subLang),
                onClick = { sheet = PlaybackSheet.SUB_LANG },
            )
        }

        settingsSection(R.string.settings_vp_section_episodes)
        settingsNote(R.string.settings_vp_section_episodes_summary)
        settingsGroup(key = "resume") {
            SettingRow(
                title = stringResource(R.string.settings_resume_playback),
                subtitle = stringResource(R.string.settings_resume_playback_description),
                value = stringResource(resume.labelRes()),
                onClick = { sheet = PlaybackSheet.RESUME },
            )

            QuickSwitchRow(
                vm = vm,
                toggle = quickToggle("vp_autoplay"),
                subtitle = stringResource(R.string.settings_autoplay_next_description),
            )
        }

        settingsSection(R.string.settings_diagnostics)
        settingsNote(R.string.settings_vp_section_diagnostics_summary)
        settingsGroup(key = "measured-stats") {
            QuickSwitchRow(
                vm = vm,
                toggle = quickToggle("vp_measured_stats"),
                subtitle = stringResource(R.string.settings_measured_stats_description),
            )

            QuickSwitchRow(
                vm = vm,
                toggle = quickToggle("vp_logging"),
                subtitle = stringResource(R.string.settings_detailed_playback_logging_description),
            )
        }
    }

    val dismiss = { sheet = null }
    // The playlist a second-level picker is editing, re-read from the live list so the value it shows
    // is the one just saved rather than the one captured when the row was tapped.
    val editing = sources.firstOrNull { it.id == overrideSource?.id }
    when (sheet) {
        PlaybackSheet.LIVE_ENGINE -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_live_tv_player),
            choices = EnginePreference.entries.map { SettingsChoice(it, engineLabel(it)) },
            selected = liveEngine,
            onSelect = { picked -> vm.edit { setLiveEnginePreference(picked) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.MULTIVIEW_TILES -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_multiview_tiles_max),
            choices = (MIN_MULTIVIEW_TILES..MAX_MULTIVIEW_TILES).map {
                SettingsChoice(it, stringResource(R.string.settings_multiview_tiles_max_value, it))
            },
            selected = multiviewTiles,
            onSelect = { picked -> vm.edit { setMultiviewTiles(picked) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.VOD_ENGINE -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_movies_series_player),
            choices = EnginePreference.entries.map { SettingsChoice(it, engineLabel(it)) },
            selected = vodEngine,
            onSelect = { picked -> vm.edit { setVodEnginePreference(picked) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.ZOOM -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_default_zoom),
            choices = ZoomMode.entries.map { SettingsChoice(it, stringResource(it.labelRes)) },
            selected = zoomModeOf(zoom),
            onSelect = { picked -> vm.edit { setDefaultZoom(picked.name) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.SURROUND -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_surround_sound),
            choices = SurroundMode.entries.map {
                SettingsChoice(it, stringResource(it.labelRes()), stringResource(it.descriptionRes()))
            },
            selected = surround,
            onSelect = { picked -> vm.edit { setSurroundMode(picked) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.AUDIO_LANG -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_preferred_audio_language),
            choices = TRACK_LANGUAGE_CODES.map { SettingsChoice(it, trackLanguageName(it)) },
            selected = audioLang,
            onSelect = { code -> vm.edit { setPreferredAudioLang(code) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.SUB_LANG -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_preferred_subtitle_language),
            choices = TRACK_LANGUAGE_CODES.map { SettingsChoice(it, trackLanguageName(it)) },
            selected = subLang,
            onSelect = { code -> vm.edit { setPreferredSubLang(code) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.RESUME -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_resume_playback),
            choices = SettingsRepository.ResumeMode.entries.map {
                SettingsChoice(it, stringResource(it.labelRes()))
            },
            selected = resume,
            onSelect = { picked -> vm.edit { setResumeMode(picked) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.LATENCY -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_live_latency),
            choices = LiveLatency.entries.map { SettingsChoice(it, stringResource(it.labelRes())) },
            selected = latency,
            onSelect = { picked ->
                // Low latency is the one choice that can make a working stream stutter, so it is
                // acknowledged before it is applied; Cancel leaves the current setting untouched.
                if (picked == LiveLatency.LOW) {
                    lowWarning = Pair({ vm.edit { setLiveLatencyMode(picked.name) } }, {})
                } else {
                    vm.edit { setLiveLatencyMode(picked.name) }
                }
            },
            onDismiss = dismiss,
        )
        PlaybackSheet.SEEK_STEP -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_seek_step),
            choices = SeekSteps.SEEK_CHOICES.map {
                SettingsChoice(it, stringResource(R.string.settings_live_buffer_seconds, it))
            },
            selected = seekStep,
            onSelect = { secs -> vm.edit { setSeekStepSec(secs) } },
            onDismiss = dismiss,
        )
        PlaybackSheet.REWIND_STEP -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_live_rewind_step),
            choices = SeekSteps.LIVE_REWIND_CHOICES.map {
                SettingsChoice(it, stringResource(R.string.settings_live_buffer_seconds, it))
            },
            selected = rewindStep,
            onSelect = { secs -> vm.edit { setLiveRewindStepSec(secs) } },
            onDismiss = dismiss,
        )
        // --- Per-playlist Live TV engine: pick the playlist, then its value ---
        PlaybackSheet.ENGINE_SOURCES -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_live_preroll_playlist_picker),
            choices = sources.map { src ->
                SettingsChoice<SourceEntity?>(
                    value = src,
                    label = src.name,
                    description = src.liveEnginePreference
                        ?.let { name -> EnginePreference.entries.firstOrNull { it.name == name } }
                        ?.let { engineLabel(it) }
                        ?: stringResource(R.string.settings_live_preroll_follow),
                )
            },
            selected = editing,
            onSelect = { src -> overrideSource = src; sheet = PlaybackSheet.ENGINE_SOURCE },
            onDismiss = { overrideSource = null; sheet = null },
        )
        PlaybackSheet.ENGINE_SOURCE -> SettingsChoiceSheet(
            title = editing?.name ?: stringResource(R.string.settings_live_tv_player),
            choices = listOf(
                SettingsChoice<String?>(null, stringResource(R.string.settings_live_preroll_follow)),
            ) + EnginePreference.entries.map { SettingsChoice<String?>(it.name, engineLabel(it)) },
            selected = editing?.liveEnginePreference,
            onSelect = { name -> editing?.let { vm.setSourceLiveEngine(it.id, name) } },
            // Back goes back one level, to the playlist list, because setting a second playlist is
            // the normal next move — not a fresh trip from the row two steps above.
            onDismiss = { sheet = PlaybackSheet.ENGINE_SOURCES },
        )

        // --- Per-playlist Live latency; Custom opens a third level for the seconds ---
        PlaybackSheet.LATENCY_SOURCES -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_live_preroll_playlist_picker),
            choices = sources.map { src ->
                SettingsChoice<SourceEntity?>(
                    value = src,
                    label = src.name,
                    description = sourceLatencyLabel(src),
                )
            },
            selected = editing,
            onSelect = { src -> overrideSource = src; sheet = PlaybackSheet.LATENCY_SOURCE },
            onDismiss = { overrideSource = null; sheet = null },
        )
        PlaybackSheet.LATENCY_SOURCE -> SettingsChoiceSheet(
            title = editing?.name ?: stringResource(R.string.settings_live_latency),
            choices = listOf(
                SettingsChoice<String?>(null, stringResource(R.string.settings_live_preroll_follow)),
            ) + LiveLatency.entries.map {
                SettingsChoice<String?>(it.name, stringResource(it.labelRes()))
            },
            selected = editing?.liveLatencyMode,
            onSelect = { name ->
                val src = editing ?: return@SettingsChoiceSheet
                val secs = sourceCustomSecs(src)
                when (LiveLatency.fromName(name ?: "")) {
                    LiveLatency.LOW -> lowWarning = Pair(
                        { vm.setSourceLiveLatency(src.id, name, secs) },
                        {},
                    )
                    // The seconds are chosen next, and Custom is committed with them — switching on
                    // open would leave a playlist on Custom with a value nobody picked.
                    LiveLatency.CUSTOM -> sheet = PlaybackSheet.LATENCY_SOURCE_CUSTOM
                    else -> vm.setSourceLiveLatency(src.id, name, FOLLOW_GLOBAL_LATENCY_SECS)
                }
            },
            onDismiss = { sheet = PlaybackSheet.LATENCY_SOURCES },
        )
        PlaybackSheet.LATENCY_SOURCE_CUSTOM -> MobileBottomSheet(
            onDismissRequest = { sheet = PlaybackSheet.LATENCY_SOURCES },
            title = editing?.name ?: stringResource(R.string.settings_live_latency_custom),
        ) {
            val src = editing
            val secs = src?.let { sourceCustomSecs(it) } ?: LiveBuffer.CUSTOM_DEFAULT
            SettingsSlider(
                title = stringResource(R.string.settings_live_latency_custom),
                value = secs,
                range = LiveBuffer.CUSTOM_MIN..LiveBuffer.CUSTOM_MAX,
                valueLabel = stringResource(R.string.settings_live_buffer_seconds, secs),
                onValueChange = { picked ->
                    if (src == null) return@SettingsSlider
                    vm.setSourceLiveLatency(src.id, LiveLatency.CUSTOM.name, picked)
                    if (LiveBuffer.isLowLatency(picked) && !LiveBuffer.isLowLatency(secs)) {
                        lowWarning = Pair(
                            {},
                            { vm.setSourceLiveLatency(src.id, null, FOLLOW_GLOBAL_LATENCY_SECS) },
                        )
                    }
                },
            )
        }

        // --- Per-playlist pre-buffer ---
        PlaybackSheet.PREROLL_SOURCES -> SettingsChoiceSheet(
            title = stringResource(R.string.settings_live_preroll_playlist_picker),
            choices = sources.map { src ->
                SettingsChoice<SourceEntity?>(
                    value = src,
                    label = src.name,
                    description = when {
                        src.livePrerollSecs == FOLLOW_GLOBAL_PREROLL ->
                            stringResource(R.string.settings_live_preroll_follow)
                        src.livePrerollSecs <= 0 -> stringResource(R.string.common_off)
                        else -> stringResource(R.string.settings_video_seconds, src.livePrerollSecs)
                    },
                )
            },
            selected = editing,
            onSelect = { src -> overrideSource = src; sheet = PlaybackSheet.PREROLL_SOURCE },
            onDismiss = { overrideSource = null; sheet = null },
        )
        PlaybackSheet.PREROLL_SOURCE -> SettingsChoiceSheet(
            title = editing?.name ?: stringResource(R.string.settings_live_preroll),
            choices = listOf(
                SettingsChoice(
                    FOLLOW_GLOBAL_PREROLL,
                    stringResource(R.string.settings_live_preroll_follow),
                ),
            ) + LiveBuffer.PREROLL_CHOICES.map { secs ->
                SettingsChoice(
                    secs,
                    if (secs <= 0) {
                        stringResource(R.string.common_off)
                    } else {
                        stringResource(R.string.settings_video_seconds, secs)
                    },
                )
            },
            selected = editing?.livePrerollSecs ?: FOLLOW_GLOBAL_PREROLL,
            onSelect = { secs -> editing?.let { vm.setSourcePreroll(it.id, secs) } },
            onDismiss = { sheet = PlaybackSheet.PREROLL_SOURCES },
        )
        // One sheet for all three sections: handing a stream to another app is a decision about
        // which kinds of content, not about a single value, so it is one panel rather than a picker.
        PlaybackSheet.EXTERNAL_PLAYER -> MobileBottomSheet(
            onDismissRequest = dismiss,
            title = stringResource(R.string.settings_external_player),
        ) {
            Text(
                text = stringResource(R.string.settings_external_player_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = MobileDimens.ScreenPaddingH,
                    vertical = MobileDimens.GapSmall,
                ),
            )
            SettingRow(
                title = stringResource(R.string.common_nav_live_tv),
                checked = externalLive,
                onCheckedChange = { on ->
                    vm.edit { setExternalPlayer(SettingsRepository.ExternalPlayerSection.LIVE_TV, on) }
                },
            )
            SettingRow(
                title = stringResource(R.string.common_nav_movies),
                checked = externalMovies,
                onCheckedChange = { on ->
                    vm.edit { setExternalPlayer(SettingsRepository.ExternalPlayerSection.MOVIES, on) }
                },
            )
            SettingRow(
                title = stringResource(R.string.common_nav_series),
                checked = externalSeries,
                onCheckedChange = { on ->
                    vm.edit { setExternalPlayer(SettingsRepository.ExternalPlayerSection.SERIES, on) }
                },
            )
        }
        null -> Unit
    }

    resetting?.let { target ->
        AlertDialog(
            modifier = Modifier.glassDialogWindow(),
            onDismissRequest = { resetting = null },
            title = { Text(stringResource(target.titleRes)) },
            text = { Text(stringResource(target.descriptionRes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (target) {
                            ResetTarget.ENGINE_PINS -> vm.clearVodEnginePins()
                            ResetTarget.ZOOM -> vm.clearSavedZoom()
                            ResetTarget.VOLUME -> vm.clearSavedVolume()
                            ResetTarget.AUDIO_DELAY -> vm.clearSavedAudioDelay()
                        }
                        resetting = null
                    },
                ) { Text(stringResource(R.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { resetting = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    lowWarning?.let { (onConfirm, onCancel) ->
        AlertDialog(
            modifier = Modifier.glassDialogWindow(),
            onDismissRequest = { lowWarning = null; onCancel() },
            title = { Text(stringResource(R.string.settings_low_latency_warning)) },
            text = { Text(stringResource(R.string.settings_low_latency_warning_description)) },
            confirmButton = {
                TextButton(onClick = { lowWarning = null; onConfirm() }) {
                    Text(stringResource(R.string.settings_low_latency_understand))
                }
            },
            dismissButton = {
                TextButton(onClick = { lowWarning = null; onCancel() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (afrWarning) {
        AlertDialog(
            modifier = Modifier.glassDialogWindow(),
            onDismissRequest = { afrWarning = false },
            title = { Text(stringResource(R.string.settings_auto_frame_rate_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_auto_frame_rate_warning_description,
                        android.os.Build.VERSION.RELEASE,
                    ),
                )
            },
            // Keeping it off is the safe answer, so it is the one that reads as the main button.
            confirmButton = {
                TextButton(onClick = { afrWarning = false }) {
                    Text(stringResource(R.string.settings_auto_frame_rate_keep_off))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { afrWarning = false; vm.edit { setAutoFrameRate(true) } },
                ) { Text(stringResource(R.string.settings_auto_frame_rate_turn_on_anyway)) }
            },
        )
    }
}

/**
 * Android 12 is where a display can be asked which refresh rates it reaches without blanking it, so
 * below that the switch has to warn before it is turned on.
 */
private val afrSafe = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

/** "3 playlists" for the per-playlist override rows, or Off when every playlist follows the global. */
@Composable
private fun overrideCountLabel(count: Int): String = if (count == 0) {
    stringResource(R.string.common_off)
} else {
    pluralStringResource(R.plurals.settings_live_preroll_overrides, count, count)
}

/** "12 items" for the reset rows, or the "nothing to forget" label when the count is zero. */
@Composable
private fun rememberedCountLabel(count: Int): String = if (count == 0) {
    stringResource(R.string.settings_reset_player_choices_none)
} else {
    pluralStringResource(R.plurals.settings_reset_player_choices_count, count, count)
}

/** A playlist's own custom latency, or the global default when it has never been given one. */
private fun sourceCustomSecs(source: SourceEntity): Int =
    source.liveLatencyCustomSecs.takeIf { it >= LiveBuffer.CUSTOM_MIN } ?: LiveBuffer.CUSTOM_DEFAULT

@Composable
private fun sourceLatencyLabel(source: SourceEntity): String {
    val mode = source.liveLatencyMode?.let { LiveLatency.fromName(it) }
    return when {
        mode == null -> stringResource(R.string.settings_live_preroll_follow)
        mode == LiveLatency.CUSTOM ->
            stringResource(R.string.settings_live_buffer_seconds, sourceCustomSecs(source))
        else -> stringResource(mode.labelRes())
    }
}

/** The stored zoom is a [ZoomMode] name; anything unrecognised falls back to the default. */
private fun zoomModeOf(name: String): ZoomMode =
    runCatching { ZoomMode.valueOf(name) }.getOrDefault(ZoomMode.FIT)

/** "ExoPlayer, then mpv" — the brand names are never translated, the joining phrase is. */
@Composable
private fun engineLabel(preference: EnginePreference): String {
    val exo = stringResource(R.string.settings_player_exoplayer)
    val mpv = stringResource(R.string.settings_player_mpv)
    return when (preference) {
        EnginePreference.EXO_FIRST -> stringResource(R.string.settings_engine_order, exo, mpv)
        EnginePreference.MPV_FIRST -> stringResource(R.string.settings_engine_order, mpv, exo)
        EnginePreference.EXO_ONLY -> stringResource(R.string.settings_engine_only, exo)
        EnginePreference.MPV_ONLY -> stringResource(R.string.settings_engine_only, mpv)
    }
}

/** The ISO-639-2 codes a stream's tracks are tagged with. Blank means "whatever the stream opens on". */
private val TRACK_LANGUAGE_CODES = listOf(
    "", "eng", "spa", "fra", "deu", "ita", "por", "nld", "rus", "ara", "hin", "zho", "jpn", "kor", "tur",
)

@Composable
private fun trackLanguageName(code: String): String = stringResource(
    when (code) {
        "eng" -> R.string.settings_language_english
        "spa" -> R.string.settings_language_spanish
        "fra" -> R.string.settings_language_french
        "deu" -> R.string.settings_language_german
        "ita" -> R.string.settings_language_italian
        "por" -> R.string.settings_language_portuguese
        "nld" -> R.string.settings_language_dutch
        "rus" -> R.string.settings_language_russian
        "ara" -> R.string.settings_language_arabic
        "hin" -> R.string.settings_language_hindi
        "zho" -> R.string.settings_language_chinese
        "jpn" -> R.string.settings_language_japanese
        "kor" -> R.string.settings_language_korean
        "tur" -> R.string.settings_language_turkish
        else -> R.string.settings_none_auto
    },
)

private fun SurroundMode.labelRes() = when (this) {
    SurroundMode.AUTO -> R.string.settings_auto
    SurroundMode.STEREO -> R.string.settings_surround_stereo
    SurroundMode.SURROUND -> R.string.settings_surround_sound
}

private fun SurroundMode.descriptionRes() = when (this) {
    SurroundMode.AUTO -> R.string.settings_surround_auto_description
    SurroundMode.STEREO -> R.string.settings_surround_stereo_description
    SurroundMode.SURROUND -> R.string.settings_surround_forced_description
}

private fun SettingsRepository.ResumeMode.labelRes() = when (this) {
    SettingsRepository.ResumeMode.AUTO -> R.string.settings_resume_always
    SettingsRepository.ResumeMode.ASK -> R.string.settings_resume_ask
    SettingsRepository.ResumeMode.NEVER -> R.string.settings_resume_never
}

private fun LiveLatency.labelRes() = when (this) {
    LiveLatency.LOW -> R.string.settings_live_latency_low
    LiveLatency.BALANCED -> R.string.settings_live_latency_balanced
    LiveLatency.STABLE -> R.string.settings_live_latency_stable
    LiveLatency.CUSTOM -> R.string.settings_live_latency_custom
}
