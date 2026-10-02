package com.streamtv.iptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

val RADIO_RE = Regex("(?i)radio|راديو|إذاعة|اذاعة")

/** Streaming M3U parser: reads line by line and emits batches, so 100k+ entries never block the UI or exhaust RAM. */
object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    suspend fun parse(reader: BufferedReader, pid: Long, batchSize: Int = 1000, onBatch: suspend (List<ChannelEntity>) -> Unit) {
        val batch = ArrayList<ChannelEntity>(batchSize)
        var name = ""; var logo = ""; var group = ""; var tvg = ""; var pending = false
        while (true) {
            val line = reader.readLine()?.trim() ?: break
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
                    val a = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = a["tvg-logo"].orEmpty(); group = a["group-title"].orEmpty().ifBlank { "Other" }
                    tvg = a["tvg-id"].orEmpty()
                    name = line.substringAfterLast(',', "").trim().ifBlank { a["tvg-name"].orEmpty() }
                    pending = true
                }
                line.startsWith("#") -> {}
                pending -> {
                    val kind = when {
                        RADIO_RE.containsMatchIn(group) -> "radio"
                        line.contains("/movie/") || line.contains("/series/") -> "movie"
                        else -> "live"
                    }
                    batch += ChannelEntity(playlistId = pid, kind = kind, name = name, logo = logo, groupTitle = group, streamUrl = line, tvgId = tvg)
                    pending = false
                    if (batch.size >= batchSize) { onBatch(ArrayList(batch)); batch.clear() }
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }
}

/** Streaming XMLTV parser (pull parser). Keeps only programmes ending after now-1h and starting within 48h. */
object XmltvParser {
    suspend fun parse(input: InputStream, pid: Long, onBatch: suspend (List<EpgEntity>) -> Unit) {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun ts(s: String?): Long = try { fmt.parse(s!!.trim())!!.time } catch (e: Exception) { 0L }
        val p = Xml.newPullParser(); p.setInput(input, null)
        val now = System.currentTimeMillis(); val batch = ArrayList<EpgEntity>()
        var ch = ""; var s = 0L; var e = 0L; var title = ""; var desc = ""; var tag = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "programme") {
                        ch = p.getAttributeValue(null, "channel") ?: ""
                        s = ts(p.getAttributeValue(null, "start")); e = ts(p.getAttributeValue(null, "stop"))
                        title = ""; desc = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    if (tag == "title" && title.isEmpty()) title = p.text
                    else if (tag == "desc" && desc.isEmpty()) desc = p.text
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "programme" && e > now - 3_600_000 && s < now + 172_800_000) {
                        batch += EpgEntity(playlistId = pid, channelTvg = ch, start = s, stop = e, title = title, descr = desc)
                        if (batch.size >= 2000) { onBatch(ArrayList(batch)); batch.clear() }
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }
}
