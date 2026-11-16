package com.music.bitchord.data.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMergerTest {
    private fun dev(id: String, online: Boolean = true) = DeviceEntry(id, id, "web", online)
    private fun welcome(devices: List<DeviceEntry>, active: String?) =
        ServerMessage.Welcome(serverMs = 1, devices = devices, activeDeviceId = active)

    @Test
    fun listsEveryRoomsDevicesOnce() {
        val merger = RoomMerger("me")
        val first = merger.feed(0, welcome(listOf(dev("me"), dev("a")), null)).single() as ServerMessage.Welcome
        assertEquals(listOf("me", "a"), first.devices.map { it.deviceId })
        val second = merger.feed(1, welcome(listOf(dev("me"), dev("a"), dev("b")), null)).single() as ServerMessage.Devices
        assertEquals(listOf("me", "a", "b"), second.devices.map { it.deviceId })
    }

    @Test
    fun aDeviceIsOnlineWhenAnyRoomHasItOnline() {
        val merger = RoomMerger("me")
        merger.feed(0, welcome(listOf(dev("me"), dev("a", online = false)), null))
        val m = merger.feed(1, ServerMessage.Devices(2, listOf(dev("a", online = true)), null)).single() as ServerMessage.Devices
        assertTrue(m.devices.first { it.deviceId == "a" }.online)
    }

    @Test
    fun thisDeviceIsActiveWhenAnyRoomSaysSo() {
        val merger = RoomMerger("me")
        merger.feed(0, welcome(listOf(dev("me"), dev("a")), "a"))
        val m = merger.feed(1, ServerMessage.Devices(2, listOf(dev("me")), "me")).single() as ServerMessage.Devices
        assertEquals("me", m.activeDeviceId)
    }

    @Test
    fun ownStateIsIgnoredAndTheFirstSpeakingRoomKeepsTheSay() {
        val merger = RoomMerger("me")
        merger.feed(0, welcome(listOf(dev("me"), dev("a")), "a"))
        merger.feed(1, welcome(listOf(dev("me"), dev("b")), "b"))
        val s = com.music.bitchord.data.connect.PlaybackState.IDLE
        assertEquals(1, merger.feed(0, ServerMessage.State(3, "a", s)).size)
        assertTrue(merger.feed(1, ServerMessage.State(4, "b", s)).isEmpty())
        assertTrue(merger.feed(0, ServerMessage.State(5, "me", s)).isEmpty())
    }

    @Test
    fun aTakeoverFromTwoRoomsIsPassedOnceAndAGoneRoomIsForgotten() {
        val merger = RoomMerger("me")
        val takeover = ServerMessage.Takeover(1, null, true)
        assertEquals(1, merger.feed(0, takeover, 1000).size)
        assertTrue(merger.feed(1, takeover, 1200).isEmpty())
        merger.feed(0, welcome(listOf(dev("me"), dev("a")), null))
        merger.feed(1, welcome(listOf(dev("me"), dev("b")), null))
        val dropped = merger.drop(1) as ServerMessage.Devices
        assertEquals(listOf("me", "a"), dropped.devices.map { it.deviceId })
        assertNull(merger.drop(0))
    }
}
