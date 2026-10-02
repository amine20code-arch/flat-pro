package com.streamtv.iptv

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()

    fun open(url: String, headers: Map<String, String> = emptyMap()): Response {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        val r = http.newCall(rb.build()).execute()
        if (!r.isSuccessful) { r.close(); error("HTTP ${r.code}") }
        return r
    }
}

fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString

class XtreamClient(base0: String, val user: String, val pass: String) {
    val base = base0.trim().trimEnd('/').let { if (it.startsWith("http", true)) it else "http://$it" }
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    private val eu = enc(user)
    private val ep = enc(pass)
    private fun api(action: String, extra: String = "") = "$base/player_api.php?username=$eu&password=$ep&action=$action$extra"
    fun epgUrl() = "$base/xmltv.php?username=$eu&password=$ep"

    private suspend fun objects(url: String, f: suspend (Map<String, String>) -> Unit) {
        Net.open(url).use { r ->
            JsonReader(InputStreamReader(r.body!!.byteStream(), "UTF-8")).use { j ->
                j.beginArray()
                while (j.hasNext()) {
                    val m = HashMap<String, String>()
                    j.beginObject()
                    while (j.hasNext()) {
                        val k = j.nextName()
                        when (j.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> m[k] = j.nextString()
                            JsonToken.BOOLEAN -> m[k] = j.nextBoolean().toString()
                            JsonToken.NULL -> j.nextNull()
                            else -> j.skipValue()
                        }
                    }
                    j.endObject()
                    f(m)
                }
                j.endArray()
            }
        }
    }

    private suspend fun pull(listAction: String, catAction: String, onBatch: suspend (List<ChannelEntity>) -> Unit, mk: (Map<String, String>, String) -> ChannelEntity) {
        val cats = HashMap<String, String>()
        runCatching { objects(api(catAction)) { cats[it["category_id"] ?: ""] = it["category_name"] ?: "Other" } }
        val batch = ArrayList<ChannelEntity>()
        objects(api(listAction)) { m ->
            batch += mk(m, cats[m["category_id"]] ?: "Other")
            if (batch.size >= 1000) { onBatch(ArrayList(batch)); batch.clear() }
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }

    suspend fun live(pid: Long, onBatch: suspend (List<ChannelEntity>) -> Unit) =
        pull("get_live_streams", "get_live_categories", onBatch) { m, g ->
            ChannelEntity(playlistId = pid, kind = if (RADIO_RE.containsMatchIn(g)) "radio" else "live",
                name = m["name"] ?: "", logo = m["stream_icon"] ?: "", groupTitle = g,
                streamUrl = "$base/live/$eu/$ep/${m["stream_id"]}.m3u8", tvgId = m["epg_channel_id"] ?: "")
        }

    suspend fun movies(pid: Long, onBatch: suspend (List<ChannelEntity>) -> Unit) =
        pull("get_vod_streams", "get_vod_categories", onBatch) { m, g ->
            ChannelEntity(playlistId = pid, kind = "movie", name = m["name"] ?: "", logo = m["stream_icon"] ?: "", groupTitle = g,
                streamUrl = "$base/movie/$eu/$ep/${m["stream_id"]}.${m["container_extension"] ?: "mp4"}",
                rating = m["rating"] ?: "", ext = m["stream_id"] ?: "")
        }

    suspend fun series(pid: Long, onBatch: suspend (List<ChannelEntity>) -> Unit) =
        pull("get_series", "get_series_categories", onBatch) { m, g ->
            ChannelEntity(playlistId = pid, kind = "series", name = m["name"] ?: "", logo = m["cover"] ?: "", groupTitle = g,
                streamUrl = "series:${m["series_id"]}", rating = m["rating"] ?: "", plot = m["plot"] ?: "")
        }

    fun episodes(seriesId: String, pid: Long): List<ChannelEntity> =
        Net.open(api("get_series_info", "&series_id=$seriesId")).use { r ->
            val root = JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject
            val out = ArrayList<ChannelEntity>()
            val eps = root.getAsJsonObject("episodes")
            eps?.entrySet()?.sortedBy { it.key.toIntOrNull() ?: 0 }?.forEach { (season, arr) ->
                arr.asJsonArray.forEach { e ->
                    val o = e.asJsonObject
                    val id = o.str("id") ?: return@forEach
                    val ext = o.str("container_extension") ?: "mp4"
                    out += ChannelEntity(id = EPISODE_ID_BASE + (id.toLongOrNull() ?: 0L), playlistId = pid, kind = "movie",
                        name = "S$season E${o.str("episode_num") ?: ""}  ${o.str("title") ?: "Episode"}",
                        streamUrl = "$base/series/$eu/$ep/$id.$ext")
                }
            }
            out
        }

    fun trailer(vodId: String): String? = runCatching {
        Net.open(api("get_vod_info", "&vod_id=$vodId")).use { r ->
            JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject
                .getAsJsonObject("info")?.str("youtube_trailer")?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

/** Stalker / MAG middleware client: handshake with MAC cookie, channel list, and per-play create_link. */
class StalkerClient(portal: String, private val mac: String) {
    private val root = portal.trim().trimEnd('/').removeSuffix("/c").trimEnd('/')
    private var api = ""
    private var token = ""
    private val ua = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    private fun hdr() = mapOf(
        "Cookie" to "mac=$mac; stb_lang=en; timezone=GMT", "User-Agent" to ua,
        "X-User-Agent" to "Model: MAG250; Link: WiFi", "Authorization" to "Bearer $token")

    private fun call(q: String): JsonElement =
        Net.open("$api?$q&JsHttpRequest=1-xml", hdr()).use { r ->
            JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject.get("js")
        }

    private fun handshake() {
        var last: Exception? = null
        for (p in listOf("$root/portal.php", "$root/server/load.php", "$root/stalker_portal/server/load.php")) {
            try {
                api = p; token = ""
                token = call("type=stb&action=handshake&token=").asJsonObject.str("token") ?: error("no token")
                runCatching { call("type=stb&action=get_profile&hd=1") }
                return
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Handshake failed")
    }

    /**
     * Streams get_all_channels straight from the socket and hands channels over in batches of 1000.
     * Nothing builds a JSON tree, so portals with 20k+ channels (30 MB+) no longer exhaust the TV's RAM.
     */
    suspend fun channels(pid: Long, onBatch: suspend (List<ChannelEntity>) -> Unit) {
        handshake()
        val genres = HashMap<String, String>()
        runCatching { call("type=itv&action=get_genres").asJsonArray.forEach { val o = it.asJsonObject; genres[o.str("id") ?: ""] = o.str("title") ?: "Other" } }
        var count = 0
        val batch = ArrayList<ChannelEntity>()
        try {
            Net.open("$api?type=itv&action=get_all_channels&JsHttpRequest=1-xml", hdr()).use { r ->
                JsonReader(InputStreamReader(r.body!!.byteStream(), "UTF-8")).use { j ->
                    j.isLenient = true
                    suspend fun readChannels() {
                        j.beginArray()
                        while (j.hasNext()) {
                            var name = ""; var cmd = ""; var logo = ""; var gid = ""
                            j.beginObject()
                            while (j.hasNext()) {
                                val k = j.nextName()
                                when (j.peek()) {
                                    JsonToken.STRING, JsonToken.NUMBER -> {
                                        val v = j.nextString()
                                        when (k) { "name" -> name = v; "cmd" -> cmd = v; "logo" -> logo = v; "tv_genre_id" -> gid = v }
                                    }
                                    JsonToken.NULL -> j.nextNull()
                                    else -> j.skipValue()
                                }
                            }
                            j.endObject()
                            if (cmd.isNotBlank()) {
                                val g = genres[gid] ?: "Other"
                                batch += ChannelEntity(playlistId = pid, kind = if (RADIO_RE.containsMatchIn(g)) "radio" else "live",
                                    name = name, logo = logo, groupTitle = g, streamUrl = "stalker:$cmd")
                                if (batch.size >= 1000) { count += batch.size; onBatch(ArrayList(batch)); batch.clear() }
                            }
                        }
                        j.endArray()
                    }
                    j.beginObject()
                    while (j.hasNext()) {
                        if (j.nextName() != "js") { j.skipValue(); continue }
                        if (j.peek() == JsonToken.BEGIN_ARRAY) { readChannels(); continue }
                        j.beginObject()
                        while (j.hasNext()) {
                            if (j.nextName() == "data" && j.peek() == JsonToken.BEGIN_ARRAY) readChannels() else j.skipValue()
                        }
                        j.endObject()
                    }
                    j.endObject()
                }
            }
        } catch (e: Exception) {
            // keep what was already received if the portal cut the connection late in a huge list
            if (count + batch.size == 0) throw e
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }

    fun resolve(cmd: String): String {
        handshake()
        val js = call("type=itv&action=create_link&cmd=" + URLEncoder.encode(cmd, "UTF-8")).asJsonObject
        return (js.str("cmd") ?: error("no link")).substringAfterLast(' ').trim()
    }
}
