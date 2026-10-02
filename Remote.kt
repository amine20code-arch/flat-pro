package com.streamtv.iptv

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.Inet4Address
import java.net.NetworkInterface

object RemoteBus {
    val text = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val play = MutableSharedFlow<String>(extraBufferCapacity = 2)
}

object RemoteInfo { var url by mutableStateOf("") }

fun localIp(): String = try {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "0.0.0.0"
} catch (e: Exception) { "0.0.0.0" }

fun qr(text: String, size: Int): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Local HTTP server: a phone on the same Wi-Fi opens the QR link and gets a D-pad, keyboard and "play URL" page. */
class RemoteServer(port: Int, private val token: String, private val onKey: (Int) -> Unit) : NanoHTTPD(port) {
    override fun serve(s: IHTTPSession): Response {
        val p = s.parms
        if (p["k"] != token) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        return when (s.uri) {
            "/key" -> { p["c"]?.toIntOrNull()?.let(onKey); newFixedLengthResponse("ok") }
            "/text" -> { p["t"]?.let { RemoteBus.text.tryEmit(it) }; newFixedLengthResponse("ok") }
            "/play" -> { p["u"]?.let { RemoteBus.play.tryEmit(it) }; newFixedLengthResponse("ok") }
            else -> newFixedLengthResponse(Response.Status.OK, "text/html", PAGE)
        }
    }

    companion object {
        private const val PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{background:#111;color:#fff;font-family:sans-serif;text-align:center}button{width:84px;height:64px;margin:5px;font-size:20px;border-radius:12px;border:0;background:#2e7bff;color:#fff}
input{width:70%;padding:12px;font-size:16px}.w{width:150px}</style></head><body>
<h2>Stream TV Remote</h2>
<div><button onclick="s(19)">&#9650;</button></div>
<div><button onclick="s(21)">&#9664;</button><button onclick="s(23)">OK</button><button onclick="s(22)">&#9654;</button></div>
<div><button onclick="s(20)">&#9660;</button></div>
<div><button class=w onclick="s(4)">Back</button><button class=w onclick="s(82)">Menu</button></div>
<div><button onclick="s(166)">CH+</button><button onclick="s(167)">CH-</button><button onclick="s(85)">Play/Pause</button></div>
<h3>Type text</h3><input id=t><button onclick="t()">Send</button>
<h3>Play a stream URL</h3><input id=u placeholder="http://..."><button onclick="p()">Play</button>
<script>
var k=new URLSearchParams(location.search).get('k');
function s(c){fetch('/key?k='+k+'&c='+c)}
function t(){var e=document.getElementById('t');fetch('/text?k='+k+'&t='+encodeURIComponent(e.value));e.value=''}
function p(){fetch('/play?k='+k+'&u='+encodeURIComponent(document.getElementById('u').value))}
</script></body></html>"""
    }
}
