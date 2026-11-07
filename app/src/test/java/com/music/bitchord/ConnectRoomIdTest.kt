package com.music.bitchord

import com.music.bitchord.data.listentogether.ListenTogether
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone and the web must land in one Connect room: the same hash of the login email (web: roomId.test.ts). */
class ConnectRoomIdTest {
    @Test
    fun hashesTheEmailTheWayTheWebDoes() {
        assertEquals("2c33f5f9e51c05093f360ef8f4dde494", ListenTogether.connectRoomIdFor("person@example.com"))
        assertEquals("2c33f5f9e51c05093f360ef8f4dde494", ListenTogether.connectRoomIdFor("  Person@Example.com "))
    }

    @Test
    fun hasNoRoomWithoutAnEmail() {
        assertNull(ListenTogether.connectRoomIdFor(""))
        assertNull(ListenTogether.connectRoomIdFor("   "))
    }
}
