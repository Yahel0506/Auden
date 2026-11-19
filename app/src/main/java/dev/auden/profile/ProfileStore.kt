package dev.auden.profile

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.ListenTogether
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

/**
 * The [AudenProfile], kept as one JSON string in the settings file (`bitchord_settings`)
 * so a backup carries it with the rest of the settings and a restore on another device
 * hands that device the same party id and Connect room.
 *
 * Nothing here logs the profile: it holds a name, a picture and the ids a party and a
 * Connect room know this listener by.
 */
object ProfileStore {

    /** In `bitchord_settings`: neither a secret nor device-local, so `AppSettings.exportPrefs` exports it. */
    const val KEY = "auden_profile"
    private const val PREFS = "bitchord_settings"

    private lateinit var prefs: SharedPreferences
    private var defaultName = "Me"
    private val lock = Any()

    private val _profile = MutableStateFlow<AudenProfile?>(null)

    /** Null only before [init]. */
    val profile: StateFlow<AudenProfile?> = _profile.asStateFlow()

    fun current(): AudenProfile? = _profile.value

    /**
     * Reads the profile, freezing one from today's accounts the first time. Idempotent.
     * Must run after [AppSettings][com.music.bitchord.data.settings.AppSettings] and
     * [ListenTogether] are initialised: the freeze reads the nickname, the device id,
     * the Google session and whether Spotify is connected.
     */
    fun init(context: Context) {
        synchronized(lock) {
            if (this::prefs.isInitialized) return
            defaultName = context.getString(R.string.profile_default_name)
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = AudenProfile.decode(prefs.getString(KEY, null))
            settle(stored, previous = null, alwaysWrite = false)
        }
    }

    /**
     * After a backup was restored over the settings file: adopts [restored], the profile it
     * carried (its raw [KEY] value), or keeps [previous] (this device's, read before the
     * restore) when it carried none. Taken from the file rather than re-read off the disk,
     * so an adoption racing the restore cannot write the old profile back over it.
     */
    fun reloadAfterRestore(restored: String?, previous: AudenProfile?) {
        synchronized(lock) {
            if (!this::prefs.isInitialized) return
            settle(AudenProfile.decode(restored), previous, alwaysWrite = true)
        }
    }

    private fun settle(stored: AudenProfile?, previous: AudenProfile?, alwaysWrite: Boolean) {
        val profile = ProfileMigration.resolve(stored, previous) {
            ProfileMigration.freeze(
                snapshot = ListenTogether.identitySnapshot(),
                defaultName = defaultName,
                now = System.currentTimeMillis(),
                newId = { UUID.randomUUID().toString() },
            )
        }
        if (alwaysWrite || profile != stored) write(profile)
        _profile.value = profile
    }

    /**
     * Renames the profile. The Listen Together nickname is the same name and is written
     * with it (the Connect room of a device with no account follows it).
     */
    fun setName(name: String) = ListenTogether.setNickname(name)

    /** Called by [ListenTogether.setNickname] only. */
    internal fun onNicknameChanged(name: String) = update { ProfileMigration.rename(it, name) }

    /** A local file path or an https URL; null or blank removes the picture. */
    fun setPhoto(photo: String?) = update { it.copy(photo = photo?.trim()?.takeIf(String::isNotEmpty)) }

    /**
     * The Connect room that overrides every other: the profile's frozen one; else a
     * Google account's, adopted into the profile the first time there is one. Null
     * means Connect falls back to the Spotify and local rooms, as before profiles.
     */
    fun accountRoom(): String? {
        val profile = _profile.value ?: return ListenTogether.accountRoomId()
        profile.connectRoom?.let { return it }
        val live = ListenTogether.accountRoomId() ?: return null
        update { ProfileMigration.adoptRoom(it, live) }
        return live
    }

    private inline fun update(change: (AudenProfile) -> AudenProfile) {
        synchronized(lock) {
            if (!this::prefs.isInitialized) return
            val current = _profile.value ?: return
            val next = change(current)
            if (next == current) return
            write(next)
            _profile.value = next
        }
    }

    private fun write(profile: AudenProfile) {
        prefs.edit().putString(KEY, AudenProfile.encode(profile)).apply()
    }
}

/** What a screen shows of the profile: the name in the current language (the placeholder while none was picked) and the photo. */
data class ProfileDisplay(val name: String, val photo: String?)

/** The profile as a screen shows it, following the store. */
@androidx.compose.runtime.Composable
fun rememberProfileDisplay(): ProfileDisplay {
    val profile by ProfileStore.profile.collectAsStateWithLifecycle()
    val placeholder = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.profile_default_name)
    val p = profile
    return ProfileDisplay(
        name = if (p == null || p.nameIsDefault) placeholder else p.name,
        photo = p?.photo,
    )
}
