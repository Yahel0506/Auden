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
    fun prefersTheChannelHandleLikeTheWeb() {
        assertEquals("d0695fb667171a671dc9e9c054acfcd7", ListenTogether.connectRoomIdFor(email = "", handle = "@SomeChannel"))
        assertEquals("d0695fb667171a671dc9e9c054acfcd7", ListenTogether.connectRoomIdFor(email = "person@example.com", handle = "@SomeChannel"))
        // A "handle" that is the email fallback is not a handle.
        assertEquals("2c33f5f9e51c05093f360ef8f4dde494", ListenTogether.connectRoomIdFor(email = "person@example.com", handle = "person@example.com"))
    }

    @Test
    fun hasNoRoomWithoutAnEmail() {
        assertNull(ListenTogether.connectRoomIdFor(""))
        assertNull(ListenTogether.connectRoomIdFor("   "))
    }
}
