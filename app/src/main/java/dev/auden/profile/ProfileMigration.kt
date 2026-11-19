package dev.auden.profile

import com.music.bitchord.data.listentogether.ListenTogether

/**
 * What the app knows about who is using it, read at the moment a profile is frozen.
 * Built by [ListenTogether.identitySnapshot] from the same places the party identity
 * has always been read from.
 */
data class IdentitySnapshot(
    /** The Listen Together nickname as stored, blank when none was chosen. */
    val nickname: String,
    /** This install's party/Connect device id. */
    val deviceId: String,
    /** The active Google login, present only when the app counts it as signed in. */
    val google: GoogleIdentity?,
    /** Whether a Spotify session is connected. */
    val spotifySignedIn: Boolean,
)

/** The active Google login and the YouTube profile it acts as (the active one, else the first). */
data class GoogleIdentity(
    val accountId: String,
    val accountName: String,
    val email: String,
    val profileId: String?,
    val profileName: String?,
    val handle: String,
    val avatar: String?,
)

/**
 * The one-time move from "the YouTube account is who you are" to an Auden profile, and
 * the few rules that keep it frozen afterwards. Pure: everything it reads is passed in.
 *
 * The rule is *freeze, don't invent*: the profile carries exactly the party user id and
 * Connect room the app computed from the accounts at the moment of the update, so no
 * party, no other device and no web player sees this one move.
 */
object ProfileMigration {

    /**
     * A new profile from [snapshot]. The name follows the party name's own order (the
     * nickname, the YouTube profile's name, the account's name, the email's local part)
     * and falls back to [defaultName], marked [AudenProfile.nameIsDefault].
     */
    fun freeze(
        snapshot: IdentitySnapshot,
        defaultName: String,
        now: Long,
        newId: () -> String,
    ): AudenProfile {
        val google = snapshot.google
        // The same order, and the same trimming, as ListenTogether.identity() before profiles.
        val inherited = snapshot.nickname.trim().takeIf { it.isNotBlank() }
            ?: google?.profileName?.takeIf { it.isNotBlank() }
            ?: google?.accountName?.takeIf { it.isNotBlank() }
            ?: google?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
        return AudenProfile(
            id = newId(),
            name = inherited ?: defaultName,
            nameIsDefault = inherited == null,
            photo = google?.avatar?.takeIf { it.startsWith("https://") },
            partyUserId = if (google != null) {
                ListenTogether.accountUserIdFor(google.accountId, google.profileId)
            } else {
                ListenTogether.guestUserIdFor(snapshot.deviceId)
            },
            connectRoom = google?.let { ListenTogether.connectRoomIdFor(email = it.email, handle = it.handle) },
            createdAt = now,
            migratedFrom = when {
                google != null -> AudenProfile.MIGRATED_YT
                snapshot.spotifySignedIn -> AudenProfile.MIGRATED_SPOTIFY
                else -> AudenProfile.MIGRATED_NONE
            },
        )
    }

    /**
     * Which profile this device goes by: the stored one; else the one it had before a
     * restore that carried none (an old or web-made backup must not reset who this is);
     * else a newly frozen one. [freeze] is only called in the last case.
     */
    fun resolve(stored: AudenProfile?, previous: AudenProfile?, freeze: () -> AudenProfile): AudenProfile =
        stored ?: previous ?: freeze()

    /**
     * A Google account connected after the profile was made, with no room frozen yet:
     * its room becomes the profile's, once. A frozen room is never replaced, and no
     * account (signed out) changes nothing.
     */
    fun adoptRoom(profile: AudenProfile, accountRoom: String?): AudenProfile =
        if (profile.connectRoom == null && !accountRoom.isNullOrBlank()) profile.copy(connectRoom = accountRoom) else profile

    /** A chosen name. Blank changes nothing: a profile always has a name. */
    fun rename(profile: AudenProfile, name: String): AudenProfile {
        val clean = name.trim().take(AudenProfile.MAX_NAME)
        if (clean.isEmpty()) return profile
        return profile.copy(name = clean, nameIsDefault = false)
    }
}
