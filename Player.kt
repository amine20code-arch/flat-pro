package com.streamtv.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val RESIZE_MODES = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)

data class PlayRequest(val items: List<ChannelEntity>, val index: Int, val resumeMs: Long = 0)

/**
 * fast = small buffers for sub-second zapping; stable = bigger buffers used after repeated rebuffering on weak ADSL/4G.
 * Retries use exponential backoff (1s, 2s, 4s, 8s, 8s) before the stream is declared offline.
 */
fun buildPlayer(ctx: Context, stable: Boolean): ExoPlayer {
    val load = (if (stable) DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
    else DefaultLoadControl.Builder().setBufferDurationsMs(3_000, 20_000, 500, 1_500)).build()
    val http = DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.18 LibVLC/3.0.18").setConnectTimeoutMs(if (stable) 15_000 else 8_000)
        .setReadTimeoutMs(if (stable) 20_000 else 12_000).setAllowCrossProtocolRedirects(true)
    val policy = object : DefaultLoadErrorHandlingPolicy(5) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (info.errorCount > 5) C.TIME_UNSET else minOf(1000L shl (info.errorCount - 1), 8000L)
    }
    val renderers = DefaultRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) // uses the FFmpeg extension if added
        .setEnableDecoderFallback(true) // another decoder is tried when the hardware one fails (HEVC / AC3)
    return ExoPlayer.Builder(ctx, renderers).setLoadControl(load)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(policy)).build()
}

fun cycleTrack(p: Player, type: Int, allowOff: Boolean) {
    val gs = p.currentTracks.groups.filter { it.type == type && it.isSupported }
    if (gs.isEmpty()) return
    val next = gs.indexOfFirst { it.isSelected } + 1
    val b = p.trackSelectionParameters.buildUpon()
    if (next >= gs.size && allowOff) b.setTrackTypeDisabled(type, true)
    else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(gs[next % gs.size].mediaTrackGroup, 0))
    p.trackSelectionParameters = b.build()
}

@Composable
fun Equalizer() {
    val t = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(24) { i ->
            val h by t.animateFloat(0.15f, 1f, infiniteRepeatable(tween(300 + (i * 53) % 400, easing = LinearEasing), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(10.dp).fillMaxHeight(h).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(req: PlayRequest, vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as? MainActivity
    var index by remember { mutableIntStateOf(req.index) }
    val item = req.items[index]
    val isLive = item.kind != "movie"
    var stable by remember { mutableStateOf(vm.stable.value) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var showOverlay by remember { mutableStateOf(true) }
    var epg by remember { mutableStateOf<List<EpgEntity>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var pendingResume by remember { mutableLongStateOf(req.resumeMs) }
    var reload by remember { mutableIntStateOf(0) }
    var resizeIdx by remember { mutableIntStateOf(vm.resize.value) }
    val counters = remember { intArrayOf(0, 0) } // [0]=rebuffer count, [1]=was ready
    val player = remember(stable) { buildPlayer(ctx, stable) }
    val viewRef = remember { arrayOfNulls<PlayerView>(1) }
    val focus = remember { FocusRequester() }
    val firstBtn = remember { FocusRequester() }
    val panel = menu || error != null

    DisposableEffect(player) { onDispose { player.release() } } // free decoder + RAM when leaving or swapping engine
    DisposableEffect(Unit) { act?.pipAllowed = true; onDispose { act?.pipAllowed = false } }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "خطأ في البث (${e.errorCodeName})" }
            override fun onPlaybackStateChanged(st: Int) {
                if (st == Player.STATE_BUFFERING && counters[1] == 1) counters[0]++
                counters[1] = if (st == Player.STATE_READY) 1 else 0
                if (counters[0] >= 4 && !stable && isLive) { counters[0] = 0; stable = true } // unstable link -> larger buffers
            }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    LaunchedEffect(item.id, player, reload) {
        error = null
        try {
            url = vm.repo.resolve(item)
            player.setMediaItem(MediaItem.fromUri(url)); player.prepare()
            if (pendingResume > 0) { player.seekTo(pendingResume); pendingResume = 0 }
            player.playWhenReady = true
            if (isLive) vm.saveProgress(item, 0, 0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { error = "تعذر الاتصال بالخادم" }
    }
    LaunchedEffect(item.id) {
        epg = if (item.tvgId.isNotBlank()) runCatching { vm.repo.nowNext(item.tvgId) }.getOrDefault(emptyList()) else emptyList()
        overlayTick++
    }
    LaunchedEffect(overlayTick) { showOverlay = true; delay(4000); showOverlay = false }
    LaunchedEffect(item.id, player) {
        while (true) {
            delay(10_000)
            if (!isLive && player.duration > 0) vm.saveProgress(item, player.currentPosition, player.duration)
        }
    }
    LaunchedEffect(panel) {
        delay(60)
        runCatching { if (panel) firstBtn.requestFocus() else focus.requestFocus() }
    }
    fun exit() {
        if (!isLive && player.duration > 0) vm.saveProgress(item, player.currentPosition, player.duration)
        onBack()
    }
    BackHandler { if (menu) menu = false else exit() }
    fun zap(d: Int) { if (req.items.size > 1) { index = (index + d + req.items.size) % req.items.size; overlayTick++ } }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || panel) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    AKey.KEYCODE_CHANNEL_UP -> { zap(1); true }
                    AKey.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
                    AKey.KEYCODE_DPAD_UP -> { if (isLive) zap(1) else overlayTick++; true }
                    AKey.KEYCODE_DPAD_DOWN -> { if (isLive) zap(-1) else overlayTick++; true }
                    AKey.KEYCODE_DPAD_LEFT -> if (!isLive) { player.seekTo(maxOf(0, player.currentPosition - 10_000)); overlayTick++; true } else false
                    AKey.KEYCODE_DPAD_RIGHT -> if (!isLive) { player.seekTo(player.currentPosition + 10_000); overlayTick++; true } else false
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        player.playWhenReady = !player.playWhenReady; overlayTick++; true
                    }
                    AKey.KEYCODE_MENU -> { menu = true; true }
                    AKey.KEYCODE_PROG_RED, AKey.KEYCODE_INFO, AKey.KEYCODE_GUIDE -> { overlayTick++; true }
                    AKey.KEYCODE_PROG_GREEN -> { cycleTrack(player, C.TRACK_TYPE_AUDIO, false); true }
                    AKey.KEYCODE_PROG_YELLOW -> { cycleTrack(player, C.TRACK_TYPE_TEXT, true); true }
                    AKey.KEYCODE_PROG_BLUE -> {
                        resizeIdx = (resizeIdx + 1) % 3
                        viewRef[0]?.resizeMode = RESIZE_MODES[resizeIdx]
                        true
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { c -> PlayerView(c).apply { useController = false; isFocusable = false; keepScreenOn = true; resizeMode = RESIZE_MODES[resizeIdx]; viewRef[0] = this } },
            update = { it.player = player }, modifier = Modifier.fillMaxSize())
        if (item.kind == "radio") Box(Modifier.align(Alignment.Center)) { Equalizer() }

        if (showOverlay && !panel) {
            val tf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xAA000000)).padding(24.dp)) {
                Text(item.name, fontSize = 26.sp, color = Color.White)
                epg.getOrNull(0)?.let { Text("الآن: ${it.title}  (${tf.format(Date(it.start))}-${tf.format(Date(it.stop))})", color = Color.White) }
                epg.getOrNull(1)?.let { Text("التالي: ${it.title}  (${tf.format(Date(it.start))})", color = Color(0xFFBBBBBB)) }
                if (isLive) Text("↑↓ أو CH+/CH-: تبديل القناة   MENU: خيارات   أخضر: الصوت   أصفر: الترجمة   أزرق: أبعاد الصورة", color = Color(0xFF999999), fontSize = 12.sp)
                else Text("←→: تقديم/إرجاع 10 ث   OK: إيقاف مؤقت   MENU: خيارات", color = Color(0xFF999999), fontSize = 12.sp)
            }
        }
        if (panel) {
            Column(Modifier.align(Alignment.Center).background(Color(0xDD000000)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(error ?: item.name, color = Color.White)
                Button(onClick = { menu = false; reload++ }, modifier = Modifier.focusRequester(firstBtn)) { Text("إعادة تحميل البث") }
                Button(onClick = { cycleTrack(player, C.TRACK_TYPE_AUDIO, false) }) { Text("مسار الصوت") }
                Button(onClick = { cycleTrack(player, C.TRACK_TYPE_TEXT, true) }) { Text("الترجمة") }
                Button(onClick = {
                    resizeIdx = (resizeIdx + 1) % 3
                    viewRef[0]?.resizeMode = RESIZE_MODES[resizeIdx]
                }) { Text("أبعاد الصورة") }
                Button(onClick = { stable = !stable }) { Text(if (stable) "التخزين: مستقر (اضغط للتبديل السريع)" else "التخزين: سريع (اضغط للمستقر)") }
                Button(onClick = {
                    if (url.isNotBlank()) runCatching {
                        player.pause()
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"))
                    }
                }) { Text("فتح في مشغّل خارجي (VLC)") }
                Button(onClick = { menu = false; act?.enterPip() }) { Text("صورة داخل صورة") }
                Button(onClick = { menu = false; if (error != null) exit() }) { Text(if (error != null) "رجوع" else "إغلاق") }
            }
        }
    }
}
