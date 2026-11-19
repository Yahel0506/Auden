package dev.auden.library.db

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The real migration path, on the host JVM: a database laid out exactly as the exported v8 schema says,
 * holding rows, opened by the generated [AudenDb] (which runs the 8 -> 9 auto-migration and then checks
 * every table against what v9 expects — the same check that throws on a phone).
 */
class AudenDbMigrationTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "auden-db-test-" + System.nanoTime()).apply { mkdirs() }
    private val file = File(dir, "auden.db")
    private var db: AudenDb? = null

    /** Room asks the context for little more than where the file is. */
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else File(dir, name)
    }

    @After
    fun tearDown() {
        db?.close()
        dir.deleteRecursively()
    }

    private fun schema(version: Int): kotlinx.serialization.json.JsonObject {
        val path = listOf("schemas", "app/schemas").map { File(it, "dev.auden.library.db.AudenDb/$version.json") }.first { it.exists() }
        return Json.parseToJsonElement(path.readText()).jsonObject["database"]!!.jsonObject
    }

    /** Creates [file] as version [version] of the exported schema, with nothing in it. */
    private fun createAt(version: Int, rows: List<String>) {
        val database = schema(version)
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            database["entities"]!!.jsonArray.forEach { entity ->
                val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                connection.execSQL(entity.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            database["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            rows.forEach { connection.execSQL(it) }
            connection.execSQL("PRAGMA user_version = $version")
        }
    }

    private fun open(): AudenDb = AudenDb.configure(Room.databaseBuilder(context, AudenDb::class.java, file.absolutePath))
        .setDriver(BundledSQLiteDriver())
        .build()
        .also { db = it }

    @Test
    fun v8DatabaseMigratesToV9KeepingEveryRow() = runBlocking {
        createAt(
            8,
            listOf(
                "INSERT INTO track (id, videoId, title, artist, isVideo, origin, addedAt) VALUES (1, 'vid1', 'Song', 'Artist', 0, 'YTM', 10)",
                "INSERT INTO track (id, localUri, title, artist, isVideo, origin, addedAt) VALUES (2, 'content://x/2', 'File', 'Me', 0, 'LOCAL', 11)",
                "INSERT INTO playlist (id, title, createdAt, updatedAt, pinned, sourceId, coverUrl) VALUES ('sp_local_1', 'Mix', 1, 2, 0, 'PL123', 'https://c')",
                "INSERT INTO playlist (id, title, createdAt, updatedAt, pinned, sourceId) VALUES ('sp_local_2', 'Mine', 3, 4, 1, NULL)",
                "INSERT INTO playlist_entry (playlistId, trackId, position) VALUES ('sp_local_1', 1, 0)",
                "INSERT INTO playlist_entry (playlistId, trackId, position) VALUES ('sp_local_1', 2, 1)",
                "INSERT INTO spotify_match (spotifyId, status, songJson, matchedAt) VALUES ('sp1', 'AUTO', '{}', 5)",
            ),
        )

        val database = open()
        val backup = database.backup()

        assertEquals(listOf(1L, 2L), backup.tracks().map { it.id })
        assertEquals(listOf("sp_local_1", "sp_local_2"), backup.playlists().map { it.id })
        assertEquals("PL123", backup.playlists().first().sourceId)
        assertEquals(listOf("vid1", null), database.playlists().tracksOf("sp_local_1").map { it.videoId })
        assertEquals(1, backup.spotifyMatches().size)
        // The new tables are there, and empty: existing copies get no snapshot.
        assertEquals(emptyList<PlaylistOriginEntity>(), backup.playlistOrigins())
        assertEquals(emptyList<FollowedArtistEntity>(), backup.followedArtists())
    }

    @Test
    fun originSurvivesPlaylistUpdatesAndGoesWithItsPlaylist() = runBlocking {
        val database = open()
        val playlists = database.playlists()
        val origins = database.origins()
        playlists.upsert(PlaylistEntity("p1", "Mix", sourceId = "spotify:playlist:abc"))
        origins.putOrigin(PlaylistOriginEntity("p1", snapshot = """["t1","t2"]""", syncedAt = 7, originTitle = "Mix"))

        // What LocalPlaylists does on every edit: the row written again with a new time and cover.
        playlists.upsert(playlists.byId("p1")!!.copy(updatedAt = 99, coverUrl = "https://new"))
        assertEquals("""["t1","t2"]""", origins.origin("p1")?.snapshot)
        assertEquals("https://new", playlists.byId("p1")?.coverUrl)

        playlists.delete("p1")
        assertNull(origins.origin("p1"))
    }

    /** Why [PlaylistDao.upsert] is not an `INSERT OR REPLACE` any more: the replace is a delete, and it cascades. */
    @Test
    fun replacingAPlaylistRowWouldHaveDroppedItsOrigin() {
        createAt(9, emptyList())
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL("INSERT INTO playlist (id, title, createdAt, updatedAt, pinned) VALUES ('p1', 'Mix', 1, 1, 0)")
            connection.execSQL("INSERT INTO playlist_origin (playlistId, snapshot) VALUES ('p1', '[]')")
            connection.execSQL("INSERT OR REPLACE INTO playlist (id, title, createdAt, updatedAt, pinned) VALUES ('p1', 'Mix', 1, 2, 0)")
            val left = connection.prepare("SELECT COUNT(*) FROM playlist_origin").use { it.step(); it.getLong(0) }
            assertEquals(0L, left)
        }
    }

    @Test
    fun followingTwiceKeepsTheFirst() = runBlocking {
        val origins = open().origins()
        origins.followAll(listOf(FollowedArtistEntity("UC1", "A", originService = "YT_MUSIC", originId = "UC1", addedAt = 1)))
        origins.followAll(listOf(FollowedArtistEntity("UC1", "A again", addedAt = 2), FollowedArtistEntity("UC2", "B", addedAt = 3)))
        assertEquals(listOf("UC2" to "B", "UC1" to "A"), origins.artists().map { it.id to it.name })
    }

    @Test
    fun backupRestoreCarriesTheNewTablesAndAnOlderFileKeepsTheDevicesOwn() = runBlocking {
        val database = open()
        val backup = database.backup()
        val track = TrackEntity(id = 1, videoId = "v1", title = "t", artist = "a")
        val playlist = PlaylistEntity("p1", "Mix", sourceId = "PL1")
        val origin = PlaylistOriginEntity("p1", snapshot = """["v1"]""", syncedAt = 5, originTitle = "Mix")
        val artist = FollowedArtistEntity("UC1", "A", originService = "YT_MUSIC", originId = "UC1", addedAt = 1)
        val entry = PlaylistEntryEntity(entryId = 1, playlistId = "p1", trackId = 1, position = 0)

        backup.replaceAll(listOf(track), listOf(playlist), listOf(entry), emptyList(), emptyList(), emptyList(), listOf(origin), listOf(artist))
        assertEquals(listOf(origin), backup.playlistOrigins())
        assertEquals(listOf(artist), backup.followedArtists())

        // A file from before v9 has neither: the device's own stay, the origin only for a playlist that came back.
        val other = PlaylistEntity("p2", "Other")
        backup.replaceAll(listOf(track), listOf(playlist, other), listOf(entry), emptyList(), emptyList(), emptyList(), null, null)
        assertEquals(listOf(origin), backup.playlistOrigins())
        assertEquals(listOf(artist), backup.followedArtists())
        backup.replaceAll(listOf(track), listOf(other), emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        assertEquals(emptyList<PlaylistOriginEntity>(), backup.playlistOrigins())

        // A new file that carries none replaces with none.
        backup.replaceAll(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertNotNull(backup.followedArtists())
        assertEquals(0, backup.followedArtists().size)
    }
}
