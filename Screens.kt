@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamtv.iptv

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.material3.Text as M3Text

enum class Section(val icon: String, val label: String) {
    SEARCH("🔍", "بحث"), LIVE("📺", "البث المباشر"), MOVIES("🎬", "أفلام"), SERIES("🍿", "مسلسلات"),
    MATCHES("⚽", "مباريات"), RADIO("📻", "راديو"), SOURCES("➕", "إضافة مصدر"), SETTINGS("⚙", "الإعدادات")
}

private val SPORT_RE = Regex("(?i)sport|bein|ssc|match|football|soccer|ligue|liga|premier|champions|كأس|رياض|مباريات|كرة")
private const val G_FAV = "★ قائمتي"
private const val G_HIST = "⏱ متابعة المشاهدة"
private const val G_ALL = "الكل"
typealias OpenFn = (List<ChannelEntity>, Int, Long) -> Unit

@Composable
fun HomeScreen(vm: MainViewModel, onPlay: (PlayRequest) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by vm.theme.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(vm.lastSection) }
    var episodes by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    val open: OpenFn = { list, idx, resume ->
        val target = list[idx]
        if (target.kind == "series") {
            scope.launch {
                val e = vm.episodes(target)
                if (e.isEmpty()) toast("لا توجد حلقات") else episodes = e
            }
        } else onPlay(PlayRequest(list, idx, resume))
    }

    NavigationDrawer(drawerContent = {
        Column(Modifier.fillMaxHeight().background(Color(0x99000000)).padding(12.dp), verticalArrangement = Arrangement.Center) {
            Section.values().forEach { s ->
                NavigationDrawerItem(selected = section == s, onClick = { section = s; vm.lastSection = s }, leadingContent = { Text(s.icon) }) { Text(s.label) }
            }
        }
    }) {
        Box(Modifier.fillMaxSize().background(theme.bg).padding(start = 80.dp, top = 16.dp, end = 12.dp)) {
            when (section) {
                Section.SEARCH -> SearchScreen(vm, open)
                Section.LIVE -> BrowseScreen(vm, "live", null, "لا توجد قنوات. افتح القائمة الجانبية وأضف مصدراً.", open)
                Section.MOVIES -> BrowseScreen(vm, "movie", null, "لا توجد أفلام. أضف مصدر Xtream يحتوي على VOD.", open)
                Section.SERIES -> BrowseScreen(vm, "series", null, "لا توجد مسلسلات. أضف مصدر Xtream يحتوي على مسلسلات.", open)
                Section.MATCHES -> BrowseScreen(vm, "live", SPORT_RE, "لا توجد فئات رياضية في مصادرك.", open)
                Section.RADIO -> BrowseScreen(vm, "radio", null, "لا توجد محطات راديو.", open)
                Section.SOURCES -> SourcesScreen(vm)
                Section.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
    episodes?.let { eps ->
        Dialog(onDismissRequest = { episodes = null }) {
            Box(Modifier.width(560.dp).heightIn(max = 480.dp).background(Color(0xEE111111)).padding(16.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(eps) { i, e ->
                        Button(onClick = { episodes = null; onPlay(PlayRequest(eps, i)) }, modifier = Modifier.fillMaxWidth()) { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** Three panes: groups -> channels -> preview window. OK on a channel = preview, OK on the preview = full screen. */
@Composable
fun BrowseScreen(vm: MainViewModel, kind: String, only: Regex?, empty: String, open: OpenFn) {
    val groupsAll by remember(kind) { vm.groups(kind) }.collectAsStateWithLifecycle(emptyList())
    val hist by vm.history.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favorites.collectAsStateWithLifecycle(emptyList())
    val groups = if (only != null) groupsAll.filter { only.containsMatchIn(it) } else groupsAll
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text(empty) }
    } else {
        BrowseBody(vm, kind, groups, hist.filter { it.kind == kind }.map { it.toItem() }, favs.filter { it.kind == kind }, open)
    }
}

@Composable
private fun BrowseBody(vm: MainViewModel, kind: String, groups: List<String>, h: List<ChannelEntity>, f: List<ChannelEntity>, open: OpenFn) {
    val ctx = LocalContext.current
    val special = buildList { if (f.isNotEmpty()) add(G_FAV); if (h.isNotEmpty()) add(G_HIST); add(G_ALL) }
    val allGroups = special + groups
    var group by remember(kind) { mutableStateOf(vm.lastGroup[kind] ?: G_ALL) }
    val cur = if (group in allGroups) group else G_ALL
    var limit by remember(kind, cur) { mutableIntStateOf(300) }
    var selId by remember(kind, cur) { mutableLongStateOf(-1L) }
    val list by remember(kind, cur, limit, f, h) {
        when (cur) {
            G_FAV -> flowOf(f)
            G_HIST -> flowOf(h)
            G_ALL -> vm.channelsAll(kind, limit)
            else -> vm.channels(kind, cur, limit)
        }
    }.collectAsStateWithLifecycle(emptyList())
    val sel = list.firstOrNull { it.id == selId }
    val selIdx = list.indexOfFirst { it.id == selId }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(Modifier.width(200.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(allGroups) { g -> RowCard(g, g == cur, "", { group = g; vm.lastGroup[kind] = g }, {}) }
        }
        LazyColumn(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(list) { c ->
                RowCard(c.name, c.id == selId, c.logo, { selId = c.id }, {
                    vm.toggleFav(c); Toast.makeText(ctx, "تم تحديث قائمتي", Toast.LENGTH_SHORT).show()
                })
            }
            item {
                // reached the end of the loaded part -> load the next 300 (large groups stay fast)
                LaunchedEffect(list.size) { if (list.size >= limit && cur != G_FAV && cur != G_HIST) limit += 300 }
            }
        }
        PreviewPane(Modifier.weight(1f).fillMaxHeight(), sel, selIdx, list, vm, open)
    }
}

@Composable
private fun RowCard(title: String, selected: Boolean, logo: String, onClick: () -> Unit, onLong: () -> Unit) {
    Card(
        onClick = onClick, onLongClick = onLong, modifier = Modifier.fillMaxWidth(),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        border = CardDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, Color.White)))
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (logo.isNotBlank()) {
                AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PreviewPane(modifier: Modifier, sel: ChannelEntity?, idx: Int, list: List<ChannelEntity>, vm: MainViewModel, open: OpenFn) {
    val ctx = LocalContext.current
    val stable by vm.stable.collectAsStateWithLifecycle()
    val player = remember(stable) { buildPlayer(ctx, stable) }
    var msg by remember { mutableStateOf("") }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { msg = "تعذر تشغيل القناة (${e.errorCodeName})" }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    LaunchedEffect(sel?.id, player) {
        player.stop(); player.clearMediaItems(); msg = ""
        val s = sel ?: return@LaunchedEffect
        if (s.kind == "series") return@LaunchedEffect
        try {
            val u = vm.repo.resolve(s)
            player.setMediaItem(MediaItem.fromUri(u)); player.prepare(); player.playWhenReady = true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { msg = "تعذر فتح القناة" }
    }
    val epg by produceState(emptyList<EpgEntity>(), sel?.id) {
        value = if (sel != null && sel.tvgId.isNotBlank()) runCatching { vm.repo.nowNext(sel.tvgId) }.getOrDefault(emptyList()) else emptyList()
    }
    fun fullscreen() {
        val s = sel ?: return
        open(list, idx, if (s.kind == "movie") player.currentPosition else 0L)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(onClick = { fullscreen() }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                when {
                    sel == null -> Text("اختر قناة ثم اضغط OK للمعاينة", color = Color.Gray)
                    sel.kind == "series" -> AsyncImage(model = sel.logo.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else -> AndroidView(
                        factory = { c -> PlayerView(c).apply { useController = false; isFocusable = false; keepScreenOn = true } },
                        update = { it.player = player }, modifier = Modifier.fillMaxSize())
                }
                if (msg.isNotBlank()) Text(msg, color = Color(0xFFFF8888))
            }
        }
        if (sel != null) {
            Text(sel.name, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sel.rating.isNotBlank() && sel.rating != "0") Text("★ ${sel.rating}", color = Color(0xFFF5C518))
                if (sel.groupTitle.isNotBlank()) Text(sel.groupTitle, color = Color.LightGray, fontSize = 13.sp)
            }
            epg.getOrNull(0)?.let { Text("الآن: ${it.title}", fontSize = 14.sp) }
            epg.getOrNull(1)?.let { Text("التالي: ${it.title}", fontSize = 13.sp, color = Color.LightGray) }
            if (sel.plot.isNotBlank()) Text(sel.plot.take(240), maxLines = 4, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { fullscreen() }) { Text(if (sel.kind == "series") "عرض الحلقات" else "ملء الشاشة") }
                Button(onClick = { vm.toggleFav(sel) }) { Text("قائمتي") }
            }
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, onFocus: () -> Unit = {}, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { M3Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.NumberPassword) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() })
}

private fun kindLabel(k: String) = when (k) { "live" -> "مباشر"; "movie" -> "فيلم"; "series" -> "مسلسل"; else -> "راديو" }

@Composable
fun SearchScreen(vm: MainViewModel, open: OpenFn) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    LaunchedEffect(Unit) { RemoteBus.text.collect { q += it } }
    LaunchedEffect(q) { if (q.length >= 2) { delay(300); results = vm.search(q) } else results = emptyList() }
    Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(q, { q = it }, "ابحث عن قناة أو فيلم أو مسلسل (لوحة مفاتيح الهاتف تعمل أيضاً)")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(results) { i, c -> RowCard(c.name + "  ·  " + kindLabel(c.kind), false, c.logo, { open(results, i, 0) }, {}) }
        }
    }
}

@Composable
fun SourcesScreen(vm: MainViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var mode by remember { mutableIntStateOf(0) } // 0 M3U, 1 Xtream, 2 Stalker
    var focused by remember { mutableIntStateOf(0) }
    val v = remember { mutableStateListOf("", "", "", "", "") } // 0 name, 1 url, 2 user/mac, 3 pass, 4 epg
    LaunchedEffect(Unit) { RemoteBus.text.collect { v[focused] = v[focused] + it } }
    val idxs = when (mode) { 0 -> listOf(0, 1, 4); 1 -> listOf(0, 1, 2, 3); else -> listOf(0, 1, 2) }
    fun label(i: Int) = when (i) {
        0 -> "الاسم"
        1 -> listOf("رابط القائمة (.m3u / .m3u8)", "الخادم (http://host:port)", "رابط البوابة (http://host/c/)")[mode]
        2 -> if (mode == 1) "اسم المستخدم" else "عنوان MAC (00:1A:79:xx:xx:xx)"
        3 -> "كلمة المرور"
        else -> "رابط EPG بصيغة XMLTV (اختياري)"
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(600.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { Text("إضافة مصدر", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("M3U", "Xtream", "Stalker").forEachIndexed { i, n -> Button(onClick = { mode = i; focused = 0 }) { Text(if (mode == i) "● $n" else n) } }
            }
        }
        idxs.forEach { i -> item(key = "f$i-$mode") { Field(v[i], { v[i] = it }, label(i), { focused = i }, secret = false) } }
        item {
            Button(enabled = !busy, onClick = {
                when (mode) { 0 -> vm.addM3u(v[0], v[1], v[4]); 1 -> vm.addXtream(v[0], v[1], v[2], v[3]); else -> vm.addStalker(v[0], v[1], v[2]) }
            }) { Text(if (busy) "جاري الاستيراد..." else "استيراد") }
        }
        item { Text(status) }
        item { Text("مصادرك", style = MaterialTheme.typography.titleMedium) }
        items(playlists) { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name} (${p.type})", modifier = Modifier.width(320.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(onClick = { vm.removePlaylist(p) }) { Text("حذف") }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val stable by vm.stable.collectAsStateWithLifecycle()
    val resize by vm.resize.collectAsStateWithLifecycle()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Text("التيمة", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTheme.values().forEach { t -> Button(onClick = { vm.setTheme(t) }) { Text(t.label) } }
            }
        }
        item {
            Text("التشغيل", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.setStable(!stable) }) { Text(if (stable) "التخزين: مستقر (للإنترنت الضعيف)" else "التخزين: سريع (تبديل سريع)") }
                Button(onClick = { vm.setResize((resize + 1) % 3) }) { Text("أبعاد الصورة: " + listOf("ملاءمة", "ملء", "تكبير")[resize]) }
            }
            Text("في المشغّل: MENU للخيارات، الأخضر للصوت، الأصفر للترجمة، الأزرق لأبعاد الصورة.", color = Color.Gray, fontSize = 12.sp)
        }
        item {
            Text("الملف الشخصي: ${profile?.name ?: ""}${if (profile?.isKids == true) " (أطفال)" else ""}")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.select(null) }) { Text("تبديل الملف الشخصي") }
        }
        item {
            Text("ريموت الهاتف", style = MaterialTheme.typography.headlineSmall)
            val url = RemoteInfo.url
            if (url.isBlank()) Text("خدمة الريموت غير متاحة (المنفذ مستعمل أو لا توجد شبكة).")
            else {
                Text("امسح الرمز بهاتف على نفس الشبكة: أسهم، لوحة مفاتيح، وبث رابط.", color = Color.LightGray)
                Spacer(Modifier.height(8.dp))
                Image(bitmap = remember(url) { qr(url, 320) }, contentDescription = "QR", modifier = Modifier.size(220.dp).background(Color.White).padding(8.dp))
                Text(url, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ProfileScreen(vm: MainViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val ps by vm.profiles.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(theme.bg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("من يشاهد؟", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ps.forEach { p ->
                Card(onClick = { if (p.pin.isBlank()) vm.select(p) else pinFor = p }, onLongClick = { vm.deleteProfile(p) },
                    scale = CardDefaults.scale(focusedScale = 1.12f),
                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White)))) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(100.dp).background(theme.primary, RoundedCornerShape(12.dp)), Alignment.Center) { Text(p.name.take(1).uppercase(), fontSize = 40.sp, color = Color.White) }
                        Spacer(Modifier.height(8.dp))
                        Text(p.name + if (p.isKids) " (أطفال)" else "")
                        if (p.pin.isNotBlank()) Text("🔒", fontSize = 12.sp)
                    }
                }
            }
            Card(onClick = { adding = true }, scale = CardDefaults.scale(focusedScale = 1.12f)) {
                Box(Modifier.size(132.dp, 160.dp), Alignment.Center) { Text("+  إضافة", fontSize = 22.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("اضغط مطولاً على OK فوق الملف لحذفه", color = Color.Gray, fontSize = 12.sp)
    }
    pinFor?.let { p ->
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { pinFor = null }) {
            Column(Modifier.background(Color(0xEE111111)).padding(24.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("الرمز السري لـ ${p.name}")
                Field(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, "الرمز", secret = true)
                if (wrong) Text("رمز خاطئ", color = Color(0xFFFF6666))
                Button(onClick = { if (pin == p.pin) { pinFor = null; vm.select(p) } else wrong = true }) { Text("موافق") }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var kids by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { adding = false }) {
            Column(Modifier.background(Color(0xEE111111)).padding(24.dp).width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ملف جديد")
                Field(name, { name = it }, "الاسم")
                Field(pin, { pin = it.filter(Char::isDigit).take(8) }, "رمز سري (اختياري)", secret = true)
                Button(onClick = { kids = !kids }) { Text(if (kids) "ملف أطفال: مفعّل (إخفاء فئات البالغين)" else "ملف أطفال: متوقف") }
                Button(onClick = { vm.addProfile(name, pin, kids); adding = false }) { Text("إنشاء") }
            }
        }
    }
}
