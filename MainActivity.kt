package com.streamtv.iptv

import android.app.Application
import android.app.PictureInPictureParams
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.darkColorScheme
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache

enum class AppTheme(val label: String, val bg: Color, val primary: Color) {
    MIDNIGHT("أزرق ليلي", Color(0xFF0B1530), Color(0xFF2E7BFF)),
    ALGERIA("الجزائر", Color(0xFF04180F), Color(0xFF0F9D58)),
    AMOLED("أسود وأحمر", Color(0xFF000000), Color(0xFFE50914)),
    NEON("بنفسجي", Color(0xFF120B2E), Color(0xFFB026FF))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamTheme(t: AppTheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = t.primary, background = t.bg, surface = t.bg)) {
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = t.primary)) {
            Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = t.bg, contentColor = Color.White)) { content() }
        }
    }
}

/** Memory-aware image loading: small RAM cache, RGB_565 bitmaps, thumbnails mostly served from disk cache. */
class StreamApp : Application(), ImageLoaderFactory {
    override fun onCreate() { super.onCreate(); System.loadLibrary("sqlcipher") }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(150L * 1024 * 1024).build() }
        .bitmapConfig(Bitmap.Config.RGB_565).crossfade(false).build()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) { imageLoader.memoryCache?.clear(); System.gc() }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    var pipAllowed = false
    private var server: RemoteServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRemote()
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val profile by vm.profile.collectAsStateWithLifecycle()
            var req by remember { mutableStateOf<PlayRequest?>(null) }
            LaunchedEffect(Unit) {
                RemoteBus.play.collect { u ->
                    req = PlayRequest(listOf(ChannelEntity(playlistId = 0, kind = "live", name = "بث من الهاتف", streamUrl = u)), 0)
                }
            }
            StreamTheme(theme) {
                val r = req
                when {
                    r != null -> PlayerScreen(r, vm) { req = null }
                    profile == null -> ProfileScreen(vm)
                    else -> HomeScreen(vm) { req = it }
                }
            }
        }
    }

    private fun startRemote() {
        val token = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        try {
            // keys are delivered inside the app (Instrumentation needs a system permission that normal apps do not have)
            val s = RemoteServer(8080, token) { code ->
                runOnUiThread {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            }
            s.start(); server = s
            RemoteInfo.url = "http://${localIp()}:8080/?k=$token"
        } catch (e: Exception) { RemoteInfo.url = "" }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (pipAllowed) enterPip() }

    // Focus-loss safety net: a D-pad key with nothing focused re-grabs focus instead of leaving the UI stuck.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && window.decorView.findFocus() == null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> window.decorView.requestFocus()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() { server?.stop(); super.onDestroy() }
}
