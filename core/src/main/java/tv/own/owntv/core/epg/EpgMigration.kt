package tv.own.owntv.core.epg

import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * Migration & registration helper: every playlist that has a guide URL (Xtream `xmltv.php` or a stored M3U `url-tvg`/manual link)
 * becomes an EPG source and is synced — so users don't lose guide data when adding IPTV playlists.
 */
class EpgMigration(
    private val store: EpgSourceStore,
    private val sourceDao: SourceDao,
    private val epgRepository: EpgRepository,
    private val epgSyncScheduler: EpgSyncScheduler? = null,
) {
    suspend fun run() {
        ensureEpgSourcesForPlaylists()
        store.markMigrated()
    }

    suspend fun ensureEpgSourcesForPlaylists() {
        runCatching {
            val existingUrls = store.getAll().map { it.url }.toMutableSet()
            for (src in sourceDao.getAllOnce()) {
                for (url in epgRepository.guideUrls(src)) {
                    if (url in existingUrls) continue
                    existingUrls += url
                    val epg = store.add(src.name, url, src.userAgent)
                    val now = System.currentTimeMillis()
                    if (epgSyncScheduler != null) {
                        epgSyncScheduler.enqueueSync(epg.id, "auto_register")
                    } else {
                        runCatching { epgRepository.refreshUrl(epg.id, epg.url, epg.userAgent) }
                            .onSuccess { store.setSynced(epg.id, now, null) }
                            .onFailure { store.setSynced(epg.id, now, it.message) }
                    }
                }
            }
        }
    }
}

