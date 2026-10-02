package com.streamtv.iptv

import android.content.Context
import android.content.SharedPreferences
import androidx.room.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

const val EPISODE_ID_BASE = 10_000_000_000L

@Entity(tableName = "profiles")
data class ProfileEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val pin: String = "", val isKids: Boolean = false)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val type: String, // m3u | xtream | stalker
    val url: String, val username: String = "", val password: String = "", val epgUrl: String = ""
)

/** kind: live | movie | series | radio.  For movies `ext` holds the Xtream vod id (used for trailers). */
@Entity(tableName = "channels", indices = [Index("kind", "groupTitle"), Index("playlistId"), Index("name")])
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long, val kind: String, val name: String, val logo: String = "",
    val groupTitle: String = "", val streamUrl: String, val tvgId: String = "",
    val rating: String = "", val plot: String = "", val ext: String = ""
)

@Entity(tableName = "history", primaryKeys = ["profileId", "itemId"])
data class HistoryEntity(
    val profileId: Long, val itemId: Long, val playlistId: Long, val kind: String, val name: String,
    val logo: String, val url: String, val tvgId: String, val position: Long, val duration: Long, val updated: Long
)

@Entity(tableName = "favorites", primaryKeys = ["profileId", "itemId"])
data class FavEntity(val profileId: Long, val itemId: Long)

@Entity(tableName = "epg", indices = [Index("channelTvg", "start")])
data class EpgEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val playlistId: Long,
    val channelTvg: String, val start: Long, val stop: Long, val title: String, val descr: String = ""
)

@Dao
interface AppDao {
    @Insert suspend fun insertItems(l: List<ChannelEntity>)
    @Insert suspend fun insertPlaylist(p: PlaylistEntity): Long
    @Query("SELECT * FROM playlists WHERE id=:id") suspend fun playlist(id: Long): PlaylistEntity?
    @Query("SELECT * FROM playlists") fun playlists(): Flow<List<PlaylistEntity>>
    @Query("DELETE FROM channels WHERE playlistId=:id") suspend fun deleteItems(id: Long)
    @Query("DELETE FROM epg WHERE playlistId=:id") suspend fun deleteEpg(id: Long)
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun deletePlaylist(id: Long)

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE kind=:k ORDER BY groupTitle") fun groups(k: String): Flow<List<String>>
    @Query("SELECT * FROM channels WHERE kind=:k AND groupTitle=:g LIMIT :n") fun byGroup(k: String, g: String, n: Int): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE kind=:k LIMIT :n") fun byKind(k: String, n: Int): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE kind=:k AND logo!='' ORDER BY RANDOM() LIMIT :n") suspend fun featured(k: String, n: Int): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :q || '%' LIMIT :n") suspend fun search(q: String, n: Int): List<ChannelEntity>
    @Query("SELECT COUNT(*) FROM channels") fun count(): Flow<Int>

    @Query("SELECT * FROM history WHERE profileId=:p ORDER BY updated DESC LIMIT 30") fun history(p: Long): Flow<List<HistoryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertHistory(h: HistoryEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addFav(f: FavEntity)
    @Query("DELETE FROM favorites WHERE profileId=:p AND itemId=:i") suspend fun removeFav(p: Long, i: Long)
    @Query("SELECT COUNT(*) FROM favorites WHERE profileId=:p AND itemId=:i") suspend fun isFav(p: Long, i: Long): Int
    @Query("SELECT c.* FROM channels c INNER JOIN favorites f ON f.itemId=c.id WHERE f.profileId=:p") fun favorites(p: Long): Flow<List<ChannelEntity>>

    @Insert suspend fun insertEpg(l: List<EpgEntity>)
    @Query("SELECT * FROM epg WHERE channelTvg=:t AND stop>:now ORDER BY start LIMIT 2") suspend fun nowNext(t: String, now: Long): List<EpgEntity>

    @Query("SELECT * FROM profiles") fun profiles(): Flow<List<ProfileEntity>>
    @Insert suspend fun insertProfile(p: ProfileEntity): Long
    @Query("DELETE FROM profiles WHERE id=:id") suspend fun deleteProfile(id: Long)
    @Query("SELECT COUNT(*) FROM profiles") suspend fun profileCount(): Int
}

@Database(entities = [ProfileEntity::class, PlaylistEntity::class, ChannelEntity::class, HistoryEntity::class, FavEntity::class, EpgEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) { inst ?: open(c.applicationContext).also { inst = it } }

        // If the key changed (Keystore reset, restored backup ...) the old file cannot be read: recreate it instead of crashing on every start.
        private fun open(c: Context): AppDb {
            val db = build(c)
            return try { db.openHelper.writableDatabase; db } catch (e: Exception) {
                runCatching { db.close() }
                c.deleteDatabase("streamtv_enc.db")
                build(c)
            }
        }

        // SQLCipher-encrypted database; the passphrase lives in Keystore-backed EncryptedSharedPreferences.
        private fun build(c: Context): AppDb =
            Room.databaseBuilder(c, AppDb::class.java, "streamtv_enc.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase(c)))
                .fallbackToDestructiveMigration().build()

        private fun passphrase(c: Context): ByteArray {
            val sp: SharedPreferences = try {
                val mk = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(c, "sec", mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            } catch (e: Exception) { c.getSharedPreferences("sec_fallback", Context.MODE_PRIVATE) }
            var k = sp.getString("dbkey", null)
            if (k == null) {
                val b = ByteArray(32); SecureRandom().nextBytes(b)
                k = b.joinToString("") { "%02x".format(it) }
                sp.edit().putString("dbkey", k).apply()
            }
            return k.toByteArray()
        }
    }
}
