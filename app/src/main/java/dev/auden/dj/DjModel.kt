package dev.auden.dj

import java.util.Locale

/** A song the DJ may play, with only what choosing needs. */
data class DjTrack(
    /** The video id, or the file's uri for a track that is only a file. */
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val art: String? = null,
    val localUri: String? = null,
    val plays: Int = 0,
    val plays30: Int = 0,
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = 0L,
    /** Known only for tracks the analyzer has already seen; never computed for the DJ's sake. */
    val bpm: Double? = null,
    /** Found by the DJ on YouTube Music's radio of something the listener knows: new to them, and on-theme. */
    val discovery: Boolean = false,
    /** The listener's playlists this song is in: the DJ draws on them, not only on what was played most. */
    val playlists: List<String> = emptyList(),
    /** For a discovery: the artist of the song it was found from, the reason it was chosen. */
    val because: String? = null,
) {
    /** The lead of a credit: "A & B", "A, B" and "A feat. B" are all A's. */
    val leadArtist: String by lazy { leadOf(artist) }
    val decade: Int? get() = year?.takeIf { it in 1900..2099 }?.let { it / 10 * 10 }
    val genreKey: String? by lazy { genre?.let(::normalizeGenre) }

    companion object {
        fun leadOf(credit: String): String =
            credit.split(" & ", ", ", " feat. ", " ft. ", " x ", " y ", ignoreCase = true).first().trim()

        /** "Rock; Pop" and "rock/pop" are rock: the first named genre, lowercased. */
        fun normalizeGenre(raw: String): String? =
            raw.split(';', '/', ',', '\u0000').firstOrNull()?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }
}

@kotlinx.serialization.Serializable
enum class DjMood(val keywords: Set<String>) {
    FOCUS(setOf("instrumental", "ambient", "piano", "classical", "clásica", "clasica", "lo-fi", "lofi", "study", "focus", "minimal", "post-rock", "soundtrack")),
    CHILL(setOf("chill", "lounge", "downtempo", "bossa", "soft", "jazz", "r&b", "soul", "trip hop", "easy listening", "dream pop", "indie pop")),
    ENERGY(setOf("rock", "metal", "punk", "dance", "edm", "electro", "house", "techno", "hip hop", "hip-hop", "rap", "reggaeton", "trap", "workout", "hard", "drum", "pop", "funk")),
    ACOUSTIC(setOf("acoustic", "acústico", "acustico", "unplugged", "folk", "singer-songwriter", "country", "bluegrass")),
    PARTY(setOf("party", "fiesta", "club", "dance", "edm", "house", "reggaeton", "dembow", "perreo", "electro", "disco", "antro")),
    WORKOUT(setOf("workout", "gym", "entreno", "ejercicio", "metal", "trap", "drill", "hip hop", "hip-hop", "rap", "edm", "hard", "drum and bass")),
    ROMANTIC(setOf("love", "amor", "romantic", "romántic", "romantico", "romántico", "ballad", "balada", "bolero", "r&b", "soul", "serenata", "corazón", "corazon")),
    MELANCHOLY(setOf("sad", "triste", "melanchol", "blues", "heartbreak", "desamor", "dolor", "lágrimas", "lagrimas", "indie folk", "slowcore", "emo")),
    HAPPY(setOf("happy", "feliz", "sunshine", "summer", "verano", "tropical", "good vibes", "dance pop", "funk", "disco", "reggae", "ska", "alegr")),
    SLEEP(setOf("sleep", "dormir", "ambient", "lullaby", "nana", "piano", "nature", "white noise", "meditation", "meditación", "meditacion")),
    ROAD_TRIP(setOf("road trip", "highway", "carretera", "viaje", "classic rock", "country", "americana", "southern", "soft rock", "heartland")),
    NOSTALGIC(setOf("nostalg", "retro", "oldies", "classic", "clásic", "vintage", "golden", "synthpop", "new wave", "80s", "90s", "70s", "60s")),
    LATIN(setOf("latin", "latino", "reggaeton", "salsa", "bachata", "cumbia", "merengue", "regional", "mexic", "corrido", "banda", "vallenato", "urbano")),
    NIGHT(setOf("night", "noche", "midnight", "nocturn", "synthwave", "deep house", "trip hop", "downtempo", "noir", "chillwave", "darkwave")),
    COFFEE(setOf("coffee", "café", "cafe", "jazz", "bossa", "lounge", "brunch", "singer-songwriter", "indie", "acoustic")),
    RAINY(setOf("rain", "lluvia", "rainy", "mellow", "shoegaze", "dream pop", "slowcore", "lo-fi", "lofi", "indie folk")),
}

/** What a block of songs has in common: the thing the DJ says out loud, and the rule the songs obey. */
sealed interface DjTheme {
    /** Identity, for not repeating a theme and for the listener's "more / less of this". */
    val key: String

    data class ByArtist(val artist: String) : DjTheme { override val key get() = "artist:${artist.lowercase(Locale.ROOT)}" }
    data class ByDecade(val decade: Int) : DjTheme { override val key get() = "decade:$decade" }
    data class ByGenre(val genre: String) : DjTheme { override val key get() = "genre:$genre" }
    data class ByMood(val mood: DjMood) : DjTheme { override val key get() = "mood:${mood.name}" }
    data class ByAlbum(val album: String, val artist: String) : DjTheme {
        override val key get() = "album:${artist.lowercase(Locale.ROOT)}:${album.lowercase(Locale.ROOT)}"
    }
    data object Favorites : DjTheme { override val key get() = "favorites" }
    data object Rediscover : DjTheme { override val key get() = "rediscover" }
    data class ByPlaylist(val name: String) : DjTheme { override val key get() = "playlist:${name.lowercase(Locale.ROOT)}" }
    data object Fresh : DjTheme { override val key get() = "fresh" }
    data object Discover : DjTheme { override val key get() = "discover" }

    /**
     * Last resort: whatever is left, with no theme to obey. The DJ still plays,
     * even for a listener with three songs and no tags, no plays and no playlists.
     */
    data object Mix : DjTheme { override val key get() = "mix" }

    /** What the listener typed, as YouTube Music understood it: songs the DJ found by searching it, not chose from the library. */
    data class ByRequest(val text: String, val taste: String? = null, val name: String? = null) : DjTheme {
        override val key get() = "request:${text.lowercase(Locale.ROOT)}"

        /** What the set is called when said or shown: the interpreter's short name when it gave one, else the ask as typed. */
        val label: String get() = name?.takeIf { it.isNotBlank() } ?: text
    }

    /** The family a theme belongs to; two blocks in a row are never of the same one unless asked for. */
    val kind: String get() = key.substringBefore(':')
}

/** A run of songs that all obey one [theme]. */
data class DjBlock(val theme: DjTheme, val tracks: List<DjTrack>)

/** What the listener asked for, understood. Every filter is optional and they combine. */
data class DjRequest(
    val text: String = "",
    val artist: String? = null,
    val decade: Int? = null,
    /** Several decades at once ("los 80 y 90", "de los 70 a los 2000"); [decade] stays the first of them. */
    val decades: List<Int> = emptyList(),
    val genre: String? = null,
    val mood: DjMood? = null,
    val favorites: Boolean = false,
    val fresh: Boolean = false,
    val rediscover: Boolean = false,
    val localOnly: Boolean = false,
    /** One album of the library, with [artist] as its artist. */
    val album: String? = null,
    /** One of the listener's playlists: a set drawn from it. */
    val playlist: String? = null,
    /** Songs new to the listener, found on YouTube Music's radio of what they play: a set of discoveries. */
    val discover: Boolean = false,
    /** For a discovery: the artist whose radio it follows, rather than the ones played most. */
    val discoverSeed: String? = null,
    /** For a discovery: follow the songs the listener has hardly played, further from their usual. */
    val discoverFar: Boolean = false,
    /** Several of the library's artists at once ("Prey y Zombie de The Neighbourhood o Nsqk"): any of them passes. */
    val artists: List<String> = emptyList(),
    /** Artists left out whatever else is asked ("sin reggaetón", "nada de X"); never relaxed. */
    val excludeArtists: List<String> = emptyList(),
    val excludeGenres: List<String> = emptyList(),
    /** Songs of the library asked for by name, in the order asked: they open the set. */
    val songIds: List<String> = emptyList(),
    /** Searches of the catalogue that would find what the library lacks, as a person would write them. */
    val queries: List<String> = emptyList(),
    /** Songs asked for by name that the library does not hold: each searched for, its first answer played. */
    val songQueries: List<String> = emptyList(),
    /** Artists asked for that the library does not hold: searched as their names. */
    val searchNames: List<String> = emptyList(),
    /** The parts of the ask that are not given up when the library falls short: "artists", "decades", "genres", "mood". */
    val strict: Set<String> = emptySet(),
    /** A short name for the session the interpreter chose ("Música clásica", "Rock de los 80"): what the DJ says and shows. */
    val title: String? = null,
    /** What was asked is not in the library at all (a genre or artist it has none of): the catalogue answers it, set after set. */
    val catalogueOnly: Boolean = false,
) {
    /** The ask names something the library cannot answer on its own, so the catalogue is searched too. */
    val needsCatalogue: Boolean get() = queries.isNotEmpty() || songQueries.isNotEmpty() || searchNames.isNotEmpty()

    /** True when the text said something the DJ could act on. */
    val allDecades: List<Int> get() = decades.ifEmpty { listOfNotNull(decade) }

    val understood: Boolean
        get() = artist != null || allDecades.isNotEmpty() || genre != null || mood != null || favorites || fresh || rediscover ||
            localOnly || album != null || playlist != null || discover ||
            artists.isNotEmpty() || excludeArtists.isNotEmpty() || excludeGenres.isNotEmpty() || songIds.isNotEmpty() || queries.isNotEmpty() || songQueries.isNotEmpty()
}
