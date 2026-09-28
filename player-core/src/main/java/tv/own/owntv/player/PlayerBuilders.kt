package tv.own.owntv.player

import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope

fun Scope.livePreviewEngine(): LivePreviewEngine = LivePreviewEngine(
    context = androidContext(),
    streamingHttp = get<tv.own.owntv.core.network.StreamingHttpClient>(),
    diagnostics = get<PlayerDiagnostics>(),
    settings = get<tv.own.owntv.core.settings.SettingsRepository>(),
    connectivity = get<tv.own.owntv.core.network.ConnectivityObserver>(),
    playbackPrefs = get<tv.own.owntv.core.player.PlaybackPrefsStore>(),
)

fun Scope.ownTVPlayer(): OwnTVPlayer = OwnTVPlayer(
    context = androidContext(),
    settings = get<tv.own.owntv.core.settings.SettingsRepository>(),
    connectivity = get<tv.own.owntv.core.network.ConnectivityObserver>(),
    streamingHttp = get<tv.own.owntv.core.network.StreamingHttpClient>(),
    diagnostics = get<PlayerDiagnostics>(),
    proxyHolder = get<tv.own.owntv.core.network.ProxyConfigHolder>(),
    vodEngineStore = get<tv.own.owntv.core.player.VodEngineStore>(),
    localeStore = get<tv.own.owntv.core.i18n.LocaleStore>(),
    playbackPrefs = get<tv.own.owntv.core.player.PlaybackPrefsStore>(),
)
