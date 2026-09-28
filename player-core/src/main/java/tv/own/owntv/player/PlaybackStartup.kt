package tv.own.owntv.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import tv.own.owntv.core.settings.SettingsRepository

object PlaybackStartup {
    fun start(
        context: Context,
        scope: CoroutineScope,
        settings: SettingsRepository,
        archiveStore: Any? = null,
    ) {
    }
}
