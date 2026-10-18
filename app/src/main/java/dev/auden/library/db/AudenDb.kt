package dev.auden.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Auden's own library database. Everything that needs to be queried (tracks,
 * playlists, lyrics variants, listens) lives here instead of in preferences.
 *
 * Schemas are exported to `app/schemas` so every migration can be tested.
 */
@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        LyricsVariantEntity::class,
        PlayEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AudenDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun playlistWrites(): PlaylistWriteDao

    companion object {
        @Volatile private var instance: AudenDb? = null

        fun get(context: Context): AudenDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AudenDb::class.java, "auden.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

        /** Adds the file-level columns the library indexer fills. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "durationText TEXT", "discNumber INTEGER", "albumArtist TEXT", "genre TEXT",
                    "year INTEGER", "folder TEXT", "sizeBytes INTEGER", "modifiedAt INTEGER",
                ).forEach { db.execSQL("ALTER TABLE track ADD COLUMN $it") }
            }
        }
    }
}
