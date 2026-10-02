package com.streamtv.iptv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

class Repository(val db: AppDb) {
    val dao = db.dao()
    // The EPG file can be 100 MB+: it is imported in the background so the channels are usable immediately.
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching { block() } }

    suspend fun addM3u(name: String, url: String, epg: String): Result<Int> = io {
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "m3u", url = url, epgUrl = epg))
        var n = 0
        try {
            Net.open(url).use { r -> M3uParser.parse(r.body!!.charStream().buffered(), pid) { dao.insertItems(it); n += it.size } }
        } catch (e: Exception) { rollback(pid); throw e }
        if (epg.isNotBlank()) bg.launch { runCatching { loadEpg(pid, epg) } }
        n
    }

    suspend fun addXtream(name: String, server: String, user: String, pass: String): Result<Int> = io {
        val c = XtreamClient(server, user, pass)
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "xtream", url = c.base, username = user, password = pass))
        var n = 0
        try { c.live(pid) { dao.insertItems(it); n += it.size } } catch (e: Exception) { rollback(pid); throw e }
        runCatching { c.movies(pid) { dao.insertItems(it); n += it.size } }
        runCatching { c.series(pid) { dao.insertItems(it); n += it.size } }
        bg.launch { runCatching { loadEpg(pid, c.epgUrl()) } }
        n
    }

    suspend fun addStalker(name: String, portal: String, mac: String): Result<Int> = io {
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "stalker", url = portal, username = mac))
        var n = 0
        try { StalkerClient(portal, mac).channels(pid) { dao.insertItems(it); n += it.size } } catch (e: Exception) { rollback(pid); throw e }
        n
    }

    private suspend fun rollback(pid: Long) { dao.deleteItems(pid); dao.deleteEpg(pid); dao.deletePlaylist(pid) }

    suspend fun removePlaylist(p: PlaylistEntity) = withContext(Dispatchers.IO) { rollback(p.id) }

    private suspend fun loadEpg(pid: Long, url: String) {
        Net.open(url).use { r ->
            var s: InputStream = BufferedInputStream(r.body!!.byteStream())
            if (url.contains(".gz")) s = GZIPInputStream(s)
            dao.deleteEpg(pid)
            XmltvParser.parse(s, pid) { dao.insertEpg(it) }
        }
    }

    /** Turns a stored item into a playable URL (Stalker links are created per play). */
    suspend fun resolve(item: ChannelEntity): String = withContext(Dispatchers.IO) {
        if (item.streamUrl.startsWith("stalker:")) {
            val p = dao.playlist(item.playlistId) ?: error("Source removed")
            StalkerClient(p.url, p.username).resolve(item.streamUrl.removePrefix("stalker:"))
        } else item.streamUrl
    }

    suspend fun episodes(item: ChannelEntity): List<ChannelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val p = dao.playlist(item.playlistId) ?: return@runCatching emptyList<ChannelEntity>()
            XtreamClient(p.url, p.username, p.password).episodes(item.streamUrl.removePrefix("series:"), p.id)
        }.getOrDefault(emptyList())
    }

    suspend fun trailer(item: ChannelEntity): String? = withContext(Dispatchers.IO) {
        if (item.kind != "movie" || item.ext.isBlank()) return@withContext null
        val p = dao.playlist(item.playlistId)?.takeIf { it.type == "xtream" } ?: return@withContext null
        XtreamClient(p.url, p.username, p.password).trailer(item.ext)
    }

    suspend fun nowNext(tvgId: String): List<EpgEntity> = dao.nowNext(tvgId, System.currentTimeMillis())
}
