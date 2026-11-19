package dev.auden.profile

import com.music.bitchord.data.listentogether.ListenTogether
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The freeze: the profile must carry exactly the party user id and Connect room the app computed before profiles
 * existed, in every account state, and must never be recomputed afterwards.
 */
class ProfileMigrationTest {

    /** The hash as ListenTogether always computed it, written out here so a change to it is noticed. */
    private fun sha32(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private fun google(
        handle: String = "",
        email: String = "",
        profileName: String? = "Channel name",
        accountName: String = "Account name",
        avatar: String? = null,
    ) = GoogleIdentity(
        accountId = "acc-1",
        accountName = accountName,
        email = email,
        profileId = "UC123",
        profileName = profileName,
        handle = handle,
        avatar = avatar,
    )

    private fun snapshot(nickname: String = "", google: GoogleIdentity? = null, spotify: Boolean = false) =
        IdentitySnapshot(nickname = nickname, deviceId = "device-1", google = google, spotifySignedIn = spotify)

    private fun freeze(snapshot: IdentitySnapshot) =
        ProfileMigration.freeze(snapshot, defaultName = "Me", now = 42L, newId = { "id-1" })

    @Test
    fun signedInWithAHandleFreezesTheAccountIdsAndTheHandleRoom() {
        val avatar = "https://lh3.googleusercontent.com/a/abc=s96-c"
        val p = freeze(snapshot(google = google(handle = "@SomeChannel", email = "person@example.com", avatar = avatar)))
        assertEquals(sha32("acc-1:UC123"), p.partyUserId)
        assertEquals(ListenTogether.accountUserIdFor("acc-1", "UC123"), p.partyUserId)
        // The same room ConnectRoomIdTest pins for the handle (and the web computes).
        assertEquals("d0695fb667171a671dc9e9c054acfcd7", p.connectRoom)
        assertEquals("Channel name", p.name)
        assertFalse(p.nameIsDefault)
        assertEquals(avatar, p.photo)
        assertEquals(avatar, p.sharablePhoto)
        assertEquals(AudenProfile.MIGRATED_YT, p.migratedFrom)
        assertEquals("id-1", p.id)
        assertEquals(42L, p.createdAt)
    }

    @Test
    fun signedInWithAnEmailOnlyFreezesTheEmailRoom() {
        val p = freeze(snapshot(google = google(email = "person@example.com", profileName = "", accountName = "")))
        assertEquals("2c33f5f9e51c05093f360ef8f4dde494", p.connectRoom)
        assertEquals(sha32("acc-1:UC123"), p.partyUserId)
        // The party name's last fallback before profiles: the email's local part.
        assertEquals("person", p.name)
        assertNull(p.photo)
    }

    @Test
    fun signedInWithNeitherHandleNorEmailHasNoFrozenRoom() {
        // accountRoomId() was null here too, so Connect keeps falling back exactly as before.
        val p = freeze(snapshot(google = google()))
        assertNull(p.connectRoom)
        assertEquals(sha32("acc-1:UC123"), p.partyUserId)
    }

    @Test
    fun theNicknameWinsOverTheAccountName() {
        val p = freeze(snapshot(nickname = "  Yael ", google = google(handle = "@SomeChannel")))
        assertEquals("Yael", p.name)
        assertEquals("Yael", p.partyName)
    }

    @Test
    fun signedOutIsAGuestOfThisDevice() {
        val p = freeze(snapshot(nickname = "Yael"))
        assertEquals(sha32("guest:device-1"), p.partyUserId)
        assertEquals(ListenTogether.guestUserIdFor("device-1"), p.partyUserId)
        assertNull(p.connectRoom)
        assertEquals("Yael", p.name)
        assertEquals(AudenProfile.MIGRATED_NONE, p.migratedFrom)
    }

    @Test
    fun signedOutWithNoNameGetsThePlaceholderAndStaysAGuestToTheParty() {
        val p = freeze(snapshot())
        assertEquals("Me", p.name)
        assertTrue(p.nameIsDefault)
        // The party was told "Guest" before profiles, and still is.
        assertNull(p.partyName)
    }

    @Test
    fun spotifyOnlyKeepsTheDynamicRooms() {
        val p = freeze(snapshot(nickname = "Yael", spotify = true))
        assertEquals(AudenProfile.MIGRATED_SPOTIFY, p.migratedFrom)
        // Spotify + local rooms stay computed as before: nothing is frozen for them.
        assertNull(p.connectRoom)
        assertEquals(sha32("guest:device-1"), p.partyUserId)
    }

    @Test
    fun aSecondRunKeepsTheStoredProfileWhateverTheAccountsSayNow() {
        val first = freeze(snapshot(google = google(handle = "@SomeChannel")))
        // Signed out since: nothing is recomputed.
        val again = ProfileMigration.resolve(stored = first, previous = null) { error("must not freeze again") }
        assertSame(first, again)
        // And it survives its own storage.
        assertEquals(first, AudenProfile.decode(AudenProfile.encode(first)))
    }

    @Test
    fun aRestoreOnASecondDeviceAdoptsTheBackedUpProfile() {
        val phoneA = freeze(snapshot(google = google(handle = "@SomeChannel")))
        val phoneB = ProfileMigration.freeze(snapshot(), defaultName = "Me", now = 7L, newId = { "id-b" })
        val restored = AudenProfile.decode(AudenProfile.encode(phoneA))
        val adopted = ProfileMigration.resolve(stored = restored, previous = phoneB) { error("must not freeze") }
        assertEquals(phoneA, adopted)
        assertEquals(phoneA.partyUserId, adopted.partyUserId)
        assertEquals("d0695fb667171a671dc9e9c054acfcd7", adopted.connectRoom)
    }

    @Test
    fun aRestoreWithNoProfileKeepsThisDevicesOwn() {
        val phoneB = freeze(snapshot(nickname = "B"))
        assertSame(phoneB, ProfileMigration.resolve(stored = null, previous = phoneB) { error("must not freeze") })
    }

    @Test
    fun aFirstLaunchFreezes() {
        val made = ProfileMigration.resolve(stored = null, previous = null) { freeze(snapshot(nickname = "N")) }
        assertEquals("N", made.name)
    }

    @Test
    fun anUnreadableStoredProfileIsTreatedAsAbsent() {
        assertNull(AudenProfile.decode(null))
        assertNull(AudenProfile.decode(""))
        assertNull(AudenProfile.decode("not json"))
        assertNull(AudenProfile.decode("""{"id":"","name":"x"}"""))
    }

    @Test
    fun aGoogleAccountConnectedLaterIsAdoptedOnce() {
        val guest = freeze(snapshot(nickname = "Yael"))
        val adopted = ProfileMigration.adoptRoom(guest, "room-1")
        assertEquals("room-1", adopted.connectRoom)
        // The user id stays the guest's: only the room is adopted.
        assertEquals(guest.partyUserId, adopted.partyUserId)
        // Another account later does not move it.
        assertEquals("room-1", ProfileMigration.adoptRoom(adopted, "room-2").connectRoom)
    }

    @Test
    fun signingOutChangesNeitherRoomNorUserId() {
        val p = freeze(snapshot(google = google(handle = "@SomeChannel")))
        assertSame(p, ProfileMigration.adoptRoom(p, null))
        val guest = freeze(snapshot())
        assertSame(guest, ProfileMigration.adoptRoom(guest, null))
        assertSame(guest, ProfileMigration.adoptRoom(guest, "  "))
    }

    @Test
    fun renamingTrimsCapsAndClearsThePlaceholder() {
        val p = freeze(snapshot())
        val renamed = ProfileMigration.rename(p, "  Yael  ")
        assertEquals("Yael", renamed.name)
        assertFalse(renamed.nameIsDefault)
        assertEquals(AudenProfile.MAX_NAME, ProfileMigration.rename(p, "x".repeat(200)).name.length)
        assertSame(p, ProfileMigration.rename(p, "   "))
        // Identity is not touched by a rename.
        assertEquals(p.partyUserId, renamed.partyUserId)
        assertEquals(p.connectRoom, renamed.connectRoom)
    }

    @Test
    fun onlyAnHttpsPhotoIsSentToAParty() {
        val p = freeze(snapshot())
        assertNull(p.copy(photo = "/data/user/0/app/files/profile.jpg").sharablePhoto)
        assertNull(p.copy(photo = "http://example.com/a.jpg").sharablePhoto)
        assertEquals("https://example.com/a.jpg", p.copy(photo = "https://example.com/a.jpg").sharablePhoto)
    }
}
