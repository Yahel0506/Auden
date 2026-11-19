package dev.auden.transfer

import dev.auden.library.Service
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLikesMigrationTest {
    private val library = FakeLibrary()

    /** The done mark, and what Liked held when it was set (it must be everything). */
    private class Mark(var writable: Boolean = true) : AccountLikesMigration.State {
        var done = false
        var marks = 0
        override fun done() = done
        override fun markDone(): Boolean {
            if (!writable) return false
            marks++
            done = true
            return true
        }
    }

    private val mark = Mark()

    private fun migration(source: MusicSource, sameSession: () -> Boolean = { true }) =
        AccountLikesMigration(ImportEngine(library, FakeResolver(), FakeSource(Service.YT_MUSIC)), source, mark, sameSession)

    private val pages = listOf(yt("n1", "n2"), yt("n3", "n4"), yt("n5"))

    @Test
    fun importsEveryPageInOrderAfterTheDevicesLikesLessTheHiddenOnes() = runBlocking {
        library.likes += song("device")
        library.hidden += "n3"
        val outcome = migration(FakeSource(Service.YT_MUSIC, likePages = pages)).run()

        assertTrue(outcome is AccountLikesMigration.Outcome.Completed)
        assertEquals(listOf("device", "n1", "n2", "n4", "n5"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 4, skipped = 1), (outcome as AccountLikesMigration.Outcome.Completed).report.likes)
        assertTrue(mark.done)
        assertEquals(1, mark.marks)
        // Written a page at a time, not a song at a time.
        assertEquals(3, library.appendCalls)
    }

    @Test
    fun doneOnlyOnceTheWholeChainWasReadAndThenNeverAgain() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, likePages = pages, failAtPage = 2)

        val first = migration(source).run()
        assertTrue(first is AccountLikesMigration.Outcome.Incomplete)
        assertFalse(mark.done)

        val second = migration(source).run()
        assertTrue(second is AccountLikesMigration.Outcome.Completed)
        assertTrue(mark.done)

        val third = migration(source).run()
        assertEquals(AccountLikesMigration.Outcome.AlreadyDone, third)
        assertEquals(2, source.likeReads)
    }

    @Test
    fun aRunAfterAFailureResumesWithoutDuplicatingOrReordering() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, likePages = pages, failAtPage = 1)

        migration(source).run()
        assertEquals(listOf("n1", "n2"), library.likes.map { it.videoId })

        migration(source).run()
        assertEquals(listOf("n1", "n2", "n3", "n4", "n5"), library.likes.map { it.videoId })
    }

    @Test
    fun runningAgainAfterTheMarkWasLostAddsNothing() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, likePages = pages)
        migration(source).run()
        mark.done = false // a backup from before the move, restored, say

        val again = migration(source).run() as AccountLikesMigration.Outcome.Completed

        assertEquals(Tally(added = 0, skipped = 5), again.report.likes)
        assertEquals(5, library.likes.size)
    }

    @Test
    fun aMarkThatCannotBeWrittenLeavesItNotDone() = runBlocking {
        mark.writable = false
        val outcome = migration(FakeSource(Service.YT_MUSIC, likePages = pages)).run()
        assertTrue(outcome is AccountLikesMigration.Outcome.Incomplete)
        assertFalse(mark.done)
    }

    @Test
    fun anAccountSwitchMidwayStopsItBeforeTheNextAccountsLikes() = runBlocking {
        var page = 0
        val source = object : MusicSource by FakeSource(Service.YT_MUSIC) {
            override suspend fun likedSongs(onPage: suspend (List<RemoteTrack>) -> Unit) {
                pages.forEach { onPage(it); page++ }
            }
        }

        val outcome = migration(source, sameSession = { page < 1 }).run()

        assertTrue(outcome is AccountLikesMigration.Outcome.Incomplete)
        assertEquals(listOf("n1", "n2"), library.likes.map { it.videoId })
        assertFalse(mark.done)
    }

    @Test
    fun anEmptyReadIsNotMarkedDoneOnItsOwn() = runBlocking {
        // A stale sign-in can answer an empty success, so nothing read is not proof of nothing liked.
        val m = migration(FakeSource(Service.YT_MUSIC))
        assertTrue(m.run() is AccountLikesMigration.Outcome.Empty)
        assertFalse(mark.done)
        assertTrue(library.likes.isEmpty())
        // ...until the caller has seen enough empty reads to accept it.
        assertTrue(m.acceptEmpty())
        assertTrue(mark.done)
    }
}
