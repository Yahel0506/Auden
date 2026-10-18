package dev.auden.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = true,
)
abstract class AudenDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao

    companion object {
        @Volatile private var instance: AudenDb? = null

        fun get(context: Context): AudenDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AudenDb::class.java, "auden.db")
                .build()
                .also { instance = it }
        }
    }
}
