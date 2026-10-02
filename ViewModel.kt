package com.streamtv.iptv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

fun HistoryEntity.toItem() = ChannelEntity(id = itemId, playlistId = playlistId, kind = kind, name = name, logo = logo, streamUrl = url, tvgId = tvgId)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(AppDb.get(app))
    private val dao = repo.dao
    val prefs = app.getSharedPreferences("ui", 0)
    val lastGroup = HashMap<String, String>()
    var lastSection: Section = Section.LIVE
    val stable = MutableStateFlow(prefs.getBoolean("stable", false))
    val resize = MutableStateFlow(prefs.getInt("resize", 0))
    private val adult = Regex("(?i)adult|xxx|porn|18\\+|sex")

    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val profile = MutableStateFlow<ProfileEntity?>(null)
    val profiles = dao.profiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val itemCount = dao.count().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val theme = MutableStateFlow(runCatching { AppTheme.valueOf(prefs.getString("theme", "MIDNIGHT")!!) }.getOrDefault(AppTheme.MIDNIGHT))

    val history: Flow<List<HistoryEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.history(p.id) }
    val favorites: Flow<List<ChannelEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.favorites(p.id) }

    init {
        viewModelScope.launch {
            if (dao.profileCount() == 0) dao.insertProfile(ProfileEntity(name = "الرئيسي"))
            val ps = dao.profiles().first()
            // a single profile without PIN is opened directly (no "who is watching" screen on every start)
            if (ps.size == 1 && ps[0].pin.isBlank() && profile.value == null) profile.value = ps[0]
        }
    }
    fun setStable(b: Boolean) { stable.value = b; prefs.edit().putBoolean("stable", b).apply() }
    fun setResize(i: Int) { resize.value = i; prefs.edit().putInt("resize", i).apply() }

    fun setTheme(t: AppTheme) { theme.value = t; prefs.edit().putString("theme", t.name).apply() }
    fun select(p: ProfileEntity?) { profile.value = p }
    fun addProfile(name: String, pin: String, kids: Boolean) = viewModelScope.launch { dao.insertProfile(ProfileEntity(name = name.ifBlank { "Profile" }, pin = pin, isKids = kids)) }
    fun deleteProfile(p: ProfileEntity) = viewModelScope.launch { if (profiles.value.size > 1) dao.deleteProfile(p.id) }

    /** Parental control: kids profiles never see adult categories. */
    fun groups(kind: String): Flow<List<String>> = combine(dao.groups(kind), profile) { g, p ->
        if (p?.isKids == true) g.filterNot { adult.containsMatchIn(it) } else g
    }
    fun channels(kind: String, group: String, limit: Int) = dao.byGroup(kind, group, limit)
    fun channelsAll(kind: String, limit: Int) = dao.byKind(kind, limit)
    suspend fun featured(kind: String) = dao.featured(kind, 8)
    suspend fun search(q: String) = dao.search(q, 200)
    suspend fun episodes(i: ChannelEntity) = repo.episodes(i)
    suspend fun trailer(i: ChannelEntity) = repo.trailer(i)

    fun toggleFav(i: ChannelEntity) = viewModelScope.launch {
        val p = profile.value ?: return@launch
        if (dao.isFav(p.id, i.id) > 0) dao.removeFav(p.id, i.id) else dao.addFav(FavEntity(p.id, i.id))
    }

    fun saveProgress(i: ChannelEntity, pos: Long, dur: Long) = viewModelScope.launch {
        val p = profile.value ?: return@launch
        dao.upsertHistory(HistoryEntity(p.id, i.id, i.playlistId, i.kind, i.name, i.logo, i.streamUrl, i.tvgId, pos, dur, System.currentTimeMillis()))
    }

    private fun run(block: suspend () -> Result<Int>) = viewModelScope.launch {
        busy.value = true; status.value = "جاري الاستيراد..."
        block().onSuccess { status.value = "تم استيراد $it عنصر" }
            .onFailure { status.value = "فشل الاستيراد: ${it.message}. تحقق من الرابط/البيانات والاتصال." }
        busy.value = false
    }
    fun addM3u(n: String, url: String, epg: String) = run { repo.addM3u(n.ifBlank { "Playlist" }, url.trim(), epg.trim()) }
    fun addXtream(n: String, s: String, u: String, p: String) = run { repo.addXtream(n.ifBlank { "Xtream" }, s.trim(), u.trim(), p.trim()) }
    fun addStalker(n: String, portal: String, mac: String) = run { repo.addStalker(n.ifBlank { "Stalker" }, portal.trim(), mac.trim()) }
    fun removePlaylist(p: PlaylistEntity) = viewModelScope.launch { repo.removePlaylist(p) }
}
