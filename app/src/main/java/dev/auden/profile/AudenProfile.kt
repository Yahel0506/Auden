package dev.auden.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Who is listening, as Auden itself knows them: not a YouTube or Spotify account.
 *
 * Accounts are where music is imported from; this is the identity the app shows and
 * the one Listen Together and Connect go by. [partyUserId] and [connectRoom] are not
 * invented here: they are what the app computed from the accounts the day the
 * profile was made (see [ProfileMigration.freeze]), kept so that signing out of an
 * account no longer moves this device to another party id or Connect room.
 */
@Serializable
data class AudenProfile(
    /** Stable and random; informative only, nothing hashes or sends it. */
    val id: String,
    val name: String,
    /**
     * True while [name] is the placeholder ("Me"), not something chosen or inherited
     * from an account. A party is then told "Guest", as it was before profiles, and
     * the UI may show the placeholder in the current language instead of [name].
     */
    val nameIsDefault: Boolean = false,
    /** A local file path, or an https URL inherited from an account. Only the latter is sent to a party. */
    val photo: String? = null,
    /** The Listen Together user id, frozen. Null only in a profile written by something other than [ProfileMigration]. */
    val partyUserId: String? = null,
    /** The Connect room, frozen; null means "the room the accounts give today", which may then be adopted once. */
    val connectRoom: String? = null,
    val createdAt: Long = 0L,
    /** [MIGRATED_YT], [MIGRATED_SPOTIFY] or [MIGRATED_NONE]: which account the profile was frozen from. */
    val migratedFrom: String = MIGRATED_NONE,
) {
    /** The name a party is told, or null for "Guest". */
    val partyName: String? get() = name.takeUnless { nameIsDefault || it.isBlank() }

    /** The photo when it can be sent to a party server: an https URL, never a local file. */
    val sharablePhoto: String? get() = photo?.takeIf { it.startsWith("https://") }

    companion object {
        const val MIGRATED_YT = "yt"
        const val MIGRATED_SPOTIFY = "spotify"
        const val MIGRATED_NONE = "none"

        /** The longest name kept: what Listen Together already capped its nickname to. */
        const val MAX_NAME = 80

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(profile: AudenProfile): String = json.encodeToString(serializer(), profile)

        /** Null for anything that is not a readable profile with an id. */
        fun decode(raw: String?): AudenProfile? = raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(serializer(), it) }.getOrNull()?.takeIf { p -> p.id.isNotBlank() }
        }
    }
}
