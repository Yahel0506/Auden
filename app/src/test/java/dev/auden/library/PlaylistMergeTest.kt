package dev.auden.library

import dev.auden.library.PlaylistMerge.Counts
import dev.auden.library.PlaylistMerge.Local
import dev.auden.library.PlaylistMerge.Mode
import dev.auden.library.PlaylistMerge.Pick
import dev.auden.library.PlaylistMerge.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D1's rules, case by case (see the table on [PlaylistMerge]). YouTube Music ids unless a test says Spotify. */
class PlaylistMergeTest {
    /** A YouTube Music copy: the id at the origin and the playable song are the same video id. */
    private fun local(vararg ids: String) = ids.map { Local(it, it) }
    private fun remote(vararg ids: String) = ids.map { Remote(it, it) }

    /** The result as ids: a local entry by its song, a source entry by its key. */
    private fun ids(local: List<Local>, remote: List<Remote>, result: PlaylistMerge.Result) = result.entries.map {
        when (it) {
            is Pick.FromLocal -> local[it.index].song
            is Pick.FromRemote -> remote[it.index].song!!
        }
    }

    private fun merge(local: List<Local>, remote: List<Remote>, base: List<String>?, mode: Mode = Mode.MERGE) =
        PlaylistMerge.merge(local, remote, base, mode).also { result -> lastIds = ids(local, remote, result) }

    private var lastIds: List<String> = emptyList()

    // ---- The truth table -------------------------------------------------------------------------------------

    @Test
    fun theSourceAddsASongItIsAddedAfterItsLastSong() {
        val r = merge(local("a", "b", "c"), remote("a", "b", "c", "d"), base = listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c", "d"), lastIds)
        assertEquals(Counts(added = 1, unchanged = 3), r.counts)
        assertEquals(listOf("a", "b", "c", "d"), r.snapshot)
        assertTrue(r.changed)
    }

    @Test
    fun theSourceRemovesASongItGoes() {
        val r = merge(local("a", "b", "c"), remote("a", "c"), base = listOf("a", "b", "c"))
        assertEquals(listOf("a", "c"), lastIds)
        assertEquals(Counts(removed = 1, unchanged = 2), r.counts)
        assertEquals(listOf("a", "c"), r.snapshot)
    }

    @Test
    fun aSongTheListenerAddedStaysAndNothingChanges() {
        val r = merge(local("a", "mine", "b"), remote("a", "b"), base = listOf("a", "b"))
        assertEquals(listOf("a", "mine", "b"), lastIds)
        assertEquals(Counts(keptLocal = 1, unchanged = 2), r.counts)
        assertFalse(r.changed)
    }

    @Test
    fun aSongTheListenerRemovedStaysRemoved() {
        val r = merge(local("a"), remote("a", "b"), base = listOf("a", "b"))
        assertEquals(listOf("a"), lastIds)
        assertEquals(Counts(unchanged = 1, stayedRemoved = 1), r.counts)
        // Still remembered, so the next refresh does not bring it back either.
        assertEquals(listOf("a", "b"), r.snapshot)
        assertFalse(r.changed)
        val again = merge(local("a"), remote("a", "b"), base = r.snapshot)
        assertEquals(listOf("a"), lastIds)
        assertEquals(0, again.counts.added)
    }

    @Test
    fun aSongTheListenerAddedThatTheSourceLaterGainsIsKeptOnce() {
        val r = merge(local("a", "x"), remote("a", "x"), base = listOf("a"))
        assertEquals(listOf("a", "x"), lastIds)
        assertEquals(Counts(unchanged = 2), r.counts)
        assertEquals(listOf("a", "x"), r.snapshot)
    }

    @Test
    fun aSongRemovedOnBothSidesIsSimplyGone() {
        val r = merge(local("a"), remote("a"), base = listOf("a", "b"))
        assertEquals(listOf("a"), lastIds)
        assertEquals(Counts(unchanged = 1), r.counts)
        assertEquals(listOf("a"), r.snapshot)
    }

    @Test
    fun bothSidesChangedTheListenersOrderWinsAndEachRuleApplies() {
        // The listener moved b first, removed c, added "mine"; the source dropped d and gained e.
        val r = merge(local("b", "a", "d", "mine"), remote("a", "b", "c", "e"), base = listOf("a", "b", "c", "d"))
        // e follows b, the last of its predecessors in the source that the copy still has.
        assertEquals(listOf("b", "e", "a", "mine"), lastIds)
        assertEquals(Counts(added = 1, removed = 1, keptLocal = 1, unchanged = 2, stayedRemoved = 1), r.counts)
        assertEquals(listOf("a", "b", "c", "e"), r.snapshot)
    }

    // ---- Order ----------------------------------------------------------------------------------------------

    @Test
    fun newSongsLandAfterTheirPredecessorInTheSourceBeforeTheListenersOwn() {
        val r = merge(local("a", "b", "mine"), remote("a", "x", "b", "y", "z"), base = listOf("a", "b"))
        assertEquals(listOf("a", "x", "b", "y", "z", "mine"), lastIds)
        assertEquals(3, r.counts.added)
    }

    @Test
    fun aNewSongWithNothingBeforeItGoesToTheTop() {
        merge(local("a", "b"), remote("n", "a", "b"), base = listOf("a", "b"))
        assertEquals(listOf("n", "a", "b"), lastIds)
    }

    @Test
    fun aPredecessorTheListenerRemovedIsSkippedForAnEarlierOne() {
        // b was removed here; c, new, goes after a.
        merge(local("a", "z"), remote("a", "b", "c"), base = listOf("a", "b"))
        assertEquals(listOf("a", "c", "z"), lastIds)
    }

    // ---- No snapshot ----------------------------------------------------------------------------------------

    @Test
    fun withoutASnapshotTheFirstRefreshOnlyAddsAndRecordsOne() {
        // a is no longer at the source, but with no base it may be the listener's: it stays.
        val r = merge(local("a", "b"), remote("b", "c"), base = null)
        assertEquals(listOf("a", "b", "c"), lastIds)
        assertEquals(Counts(added = 1, keptLocal = 1, unchanged = 1), r.counts)
        assertEquals(listOf("b", "c"), r.snapshot)

        // From then on the snapshot does the rest: c is the source's and goes when it does; a stays the listener's.
        val next = merge(local("a", "b", "c"), remote("b"), base = r.snapshot)
        assertEquals(listOf("a", "b"), lastIds)
        assertEquals(Counts(removed = 1, keptLocal = 1, unchanged = 1), next.counts)
    }

    @Test
    fun withoutASnapshotASongTheListenerRemovedComesBackOnce() {
        val r = merge(local("a"), remote("a", "b"), base = null)
        assertEquals(listOf("a", "b"), lastIds)
        assertEquals(1, r.counts.added)
        assertEquals(0, r.counts.stayedRemoved)
    }

    @Test
    fun anEmptyCopyWithASnapshotTakesOnlyWhatIsNew() {
        merge(emptyList(), remote("a", "b"), base = listOf("a"))
        assertEquals(listOf("b"), lastIds)
    }

    // ---- Duplicates -----------------------------------------------------------------------------------------

    @Test
    fun theSourcesDuplicatesAreTakenOnce() {
        val r = merge(local("a"), remote("a", "b", "a", "c", "c", "b"), base = listOf("a"))
        assertEquals(listOf("a", "b", "c"), lastIds)
        assertEquals(2, r.counts.added)
        assertEquals(listOf("a", "b", "c"), r.snapshot)
    }

    @Test
    fun theCopysDuplicatesAreLeftAloneAndAllGoWhenTheSourceDropsThem() {
        val kept = merge(local("a", "a", "b"), remote("a", "b"), base = listOf("a", "b"))
        assertEquals(listOf("a", "a", "b"), lastIds)
        assertFalse(kept.changed)
        val dropped = merge(local("a", "b", "b"), remote("a"), base = listOf("a", "b"))
        assertEquals(listOf("a"), lastIds)
        assertEquals(2, dropped.counts.removed)
    }

    // ---- Spotify ---------------------------------------------------------------------------------------------

    @Test
    fun aSpotifyTrackWithNoMatchIsReportedNotAddedAndAddedOnceItHasOne() {
        val copy = listOf(Local("s1", "v1"))
        val r = merge(copy, listOf(Remote("s1", "v1"), Remote("s2", null)), base = listOf("s1"))
        assertEquals(listOf("v1"), lastIds)
        assertEquals(listOf(1), r.unmatched)
        assertEquals(Counts(unchanged = 1, unmatched = 1), r.counts)
        // Left out of the snapshot: it was never in the copy, so it is not "removed by the listener".
        assertEquals(listOf("s1"), r.snapshot)

        val later = merge(copy, listOf(Remote("s1", "v1"), Remote("s2", "v2")), base = r.snapshot)
        assertEquals(listOf("v1", "v2"), lastIds)
        assertEquals(1, later.counts.added)
    }

    @Test
    fun aSongWithNoSpotifyIdIsTheListenersAndAlwaysStays() {
        val r = merge(listOf(Local("s1", "v1"), Local(null, "mine")), listOf(Remote("s2", "v2")), base = listOf("s1"))
        // s1 was the source's and went; v2 has nothing before it in the source, so it goes to the top.
        assertEquals(listOf("v2", "mine"), lastIds)
        assertEquals(Counts(added = 1, removed = 1, keptLocal = 1), r.counts)
    }

    @Test
    fun twoSpotifyTracksThatAreOneSongNeverTakeItOutOrAddItTwice() {
        // The source swapped s1 for s1b, another release matched to the same video.
        val r = merge(listOf(Local("s1", "v1")), listOf(Remote("s1b", "v1")), base = listOf("s1"))
        assertEquals(listOf("v1"), lastIds)
        assertEquals(Counts(unchanged = 1), r.counts)
        assertFalse(r.changed)
        assertEquals(listOf("s1b"), r.snapshot)

        val both = merge(listOf(Local("s1", "v1")), listOf(Remote("s1", "v1"), Remote("s1b", "v1")), base = listOf("s1"))
        assertEquals(listOf("v1"), lastIds)
        assertEquals(0, both.counts.added)
    }

    // ---- Replace by the source --------------------------------------------------------------------------------

    @Test
    fun replaceMakesTheCopyTheSourceKeepingTheEntriesItHad() {
        val copy = local("b", "mine", "a")
        val r = merge(copy, remote("a", "b", "c"), base = listOf("a", "b"), mode = Mode.REPLACE)
        assertEquals(listOf("a", "b", "c"), lastIds)
        assertEquals(listOf(Pick.FromLocal(2), Pick.FromLocal(0), Pick.FromRemote(2)), r.entries)
        assertEquals(Counts(added = 1, removed = 1, unchanged = 2), r.counts)
        assertEquals(listOf("a", "b", "c"), r.snapshot)
        assertTrue(r.changed)
    }

    @Test
    fun replaceBringsBackWhatTheListenerRemoved() {
        val r = merge(local("a"), remote("a", "b"), base = listOf("a", "b"), mode = Mode.REPLACE)
        assertEquals(listOf("a", "b"), lastIds)
        assertEquals(1, r.counts.added)
    }

    @Test
    fun replaceOfAnIdenticalCopyChangesNothing() {
        val r = merge(local("a", "b"), remote("a", "b"), base = null, mode = Mode.REPLACE)
        assertFalse(r.changed)
        assertEquals(Counts(unchanged = 2), r.counts)
    }

    @Test
    fun replaceReordersToTheSource() {
        val r = merge(local("b", "a"), remote("a", "b"), base = listOf("a", "b"), mode = Mode.REPLACE)
        assertEquals(listOf("a", "b"), lastIds)
        assertTrue(r.changed)
        assertEquals(Counts(unchanged = 2), r.counts)
    }

    @Test
    fun replaceLeavesTheUnmatchedOutAndTakesDuplicatesOnce() {
        val r = merge(
            listOf(Local("s1", "v1")),
            listOf(Remote("s1", "v1"), Remote("s2", null), Remote("s3", "v3"), Remote("s3", "v3"), Remote("s4", "v3")),
            base = null,
            mode = Mode.REPLACE,
        )
        assertEquals(listOf("v1", "v3"), lastIds)
        assertEquals(listOf(1), r.unmatched)
        assertEquals(listOf("s1", "s3", "s4"), r.snapshot)
    }

    // ---- The listener's own changes, for a status line ----------------------------------------------------------

    @Test
    fun localChangesCountTheListenersEditsAgainstTheSnapshot() {
        assertEquals(PlaylistMerge.LocalChanges(added = 1, removed = 1), PlaylistMerge.localChanges(local("a", "mine"), listOf("a", "b")))
        assertFalse(PlaylistMerge.localChanges(local("a", "b"), listOf("a", "b"))!!.any)
        assertEquals(1, PlaylistMerge.localChanges(listOf(Local(null, "x")), emptyList())!!.added)
        assertNull(PlaylistMerge.localChanges(local("a"), null))
    }
}
