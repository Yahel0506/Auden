package dev.auden.transfer

/**
 * The one move of the YouTube Music account's likes into Auden's own, for a listener who was signed in when
 * likes became Auden's. Until now those hearts were only ever read live off the account (and gone with it); after
 * this they are in Liked on the device like any other.
 *
 * - **Read-only** against YouTube Music: it reads Liked Music and writes nothing anywhere but Liked.
 * - **Minus what was un-liked here**: a song the listener took the heart off while likes stayed on the device
 *   ([ImportLibrary.hiddenLikes]) stays off.
 * - **In order**, after the likes already on the device, a page at a time (one write of the store per page).
 * - **Done only when the whole chain was read.** A page that fails, or the account changing midway, leaves it
 *   not done; the next run reads from the start again, and what the last one stored is simply skipped, so it
 *   picks up where it stopped without duplicating or reordering anything. Safe to run any number of times.
 *
 * Pure: the engine, the store of the done mark and the session check come in from [TransferMigrations].
 */
class AccountLikesMigration(
    private val engine: ImportEngine,
    private val source: MusicSource,
    private val state: State,
    /** False once the account the run started under is no longer the active one. */
    private val sameSession: () -> Boolean = { true },
) {
    interface State {
        fun done(): Boolean

        /**
         * Makes everything imported durable and marks the migration done, both in one write; false when that
         * write failed (the migration then stays not done).
         */
        fun markDone(): Boolean
    }

    sealed interface Outcome {
        data object AlreadyDone : Outcome

        data class Completed(val report: ImportReport) : Outcome

        /**
         * The read finished and delivered nothing. That is not proof the account has no likes — a stale sign-in can
         * answer an empty success — so it is not marked done here; the caller decides when enough empty reads are enough.
         */
        data object Empty : Outcome

        /** Stopped before the end; [report] says what was stored before it did. */
        data class Incomplete(val report: ImportReport) : Outcome
    }

    /** Marks it done after repeated empty reads, once the caller is satisfied the account really has no likes. */
    fun acceptEmpty(): Boolean = state.markDone()

    suspend fun run(onProgress: (ImportProgress) -> Unit = {}): Outcome {
        if (state.done()) return Outcome.AlreadyDone
        val report = engine.importLikes(SessionBound(source, sameSession), onProgress)
        if (!report.complete) return Outcome.Incomplete(report)
        if (report.likes == Tally()) return Outcome.Empty
        return if (state.markDone()) Outcome.Completed(report) else Outcome.Incomplete(report)
    }

    /** [source], refusing every page once the account has changed — LikeState-style, never the next account's likes. */
    private class SessionBound(private val source: MusicSource, private val sameSession: () -> Boolean) : MusicSource by source {
        override suspend fun likedSongs(onPage: suspend (List<RemoteTrack>) -> Unit) {
            if (!sameSession()) throw IncompleteReadException("the account changed")
            source.likedSongs { page ->
                if (!sameSession()) throw IncompleteReadException("the account changed")
                onPage(page)
            }
        }
    }
}
