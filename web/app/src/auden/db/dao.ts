import { notifyStores } from './notify';
import {
  openAudenDb,
  type LyricsVariantRow,
  type PlaylistEntryRow,
  type PlaylistRow,
  type SongLyricsPrefRow,
  type SpotifyMatchRow,
  type TrackRow,
  TrackOrigin,
} from './schema';

/**
 * Thin CRUD over the stores. IndexedDB has no foreign keys, so the Room cascades (a deleted playlist
 * takes its entries, a deleted track takes its entries and events) are done in the same transaction.
 * Dense reordering waits for Phase 3 and its UI.
 */

/** A track row with Room's defaults, for the fields a caller does not know. */
export function newTrack(partial: Pick<TrackRow, 'title' | 'artist'> & Partial<TrackRow>): TrackRow {
  return {
    videoId: null,
    spotifyId: null,
    isrc: null,
    localUri: null,
    path: null,
    artistId: null,
    album: null,
    albumId: null,
    thumbnailUrl: null,
    durationMs: null,
    durationText: null,
    trackNumber: null,
    discNumber: null,
    albumArtist: null,
    genre: null,
    year: null,
    folder: null,
    sizeBytes: null,
    modifiedAt: null,
    isVideo: false,
    origin: TrackOrigin.YTM,
    addedAt: Date.now(),
    identStatus: null,
    identSource: null,
    suggestedVideoId: null,
    suggestedTitle: null,
    suggestedArtist: null,
    identifiedAt: null,
    ...partial,
  };
}

export const tracks = {
  async all(): Promise<TrackRow[]> {
    return (await openAudenDb()).getAll('track');
  },
  async get(id: number): Promise<TrackRow | undefined> {
    return (await openAudenDb()).get('track', id);
  },
  async byVideoId(videoId: string): Promise<TrackRow[]> {
    return (await openAudenDb()).getAllFromIndex('track', 'videoId', videoId);
  },
  async byLocalUri(localUri: string): Promise<TrackRow | undefined> {
    return (await openAudenDb()).getFromIndex('track', 'localUri', localUri);
  },
  /** Inserts, or rejects with a ConstraintError when `localUri` is taken. Returns the new id. */
  async add(row: TrackRow): Promise<number> {
    const db = await openAudenDb();
    const { id: _ignored, ...rest } = row;
    const id = await db.add('track', rest as TrackRow);
    notifyStores('track');
    return id;
  },
  /** Replaces a row that has an id. */
  async put(row: TrackRow & { id: number }): Promise<void> {
    await (await openAudenDb()).put('track', row);
    notifyStores('track');
  },
  /** Removes the track, its playlist entries and its play events. */
  async remove(id: number): Promise<void> {
    const db = await openAudenDb();
    const tx = db.transaction(['track', 'playlist_entry', 'play_event'], 'readwrite');
    const entries = tx.objectStore('playlist_entry');
    for (let c = await entries.index('trackId').openKeyCursor(id); c; c = await c.continue()) {
      await entries.delete(c.primaryKey);
    }
    const events = tx.objectStore('play_event');
    for (let c = await events.index('trackId').openKeyCursor(id); c; c = await c.continue()) {
      await events.delete(c.primaryKey);
    }
    await tx.objectStore('track').delete(id);
    await tx.done;
    notifyStores('track', 'playlist_entry', 'play_event');
  },
};

export const playlists = {
  async all(): Promise<PlaylistRow[]> {
    return (await openAudenDb()).getAll('playlist');
  },
  async get(id: string): Promise<PlaylistRow | undefined> {
    return (await openAudenDb()).get('playlist', id);
  },
  async create(title: string, extra: Partial<Omit<PlaylistRow, 'id' | 'title'>> = {}): Promise<PlaylistRow> {
    const now = Date.now();
    const row: PlaylistRow = {
      id: `local:playlist:${crypto.randomUUID()}`,
      title,
      createdAt: now,
      updatedAt: now,
      pinned: false,
      sourceId: null,
      coverUrl: null,
      ...extra,
    };
    await (await openAudenDb()).add('playlist', row);
    notifyStores('playlist');
    return row;
  },
  async update(id: string, patch: Partial<Omit<PlaylistRow, 'id'>>): Promise<void> {
    const db = await openAudenDb();
    const tx = db.transaction('playlist', 'readwrite');
    const current = await tx.store.get(id);
    if (!current) {
      await tx.done;
      return;
    }
    await tx.store.put({ ...current, ...patch, updatedAt: Date.now() });
    await tx.done;
    notifyStores('playlist');
  },
  /** Removes the playlist and its entries (not the tracks). */
  async remove(id: string): Promise<void> {
    const db = await openAudenDb();
    const tx = db.transaction(['playlist', 'playlist_entry'], 'readwrite');
    const entries = tx.objectStore('playlist_entry');
    const range = IDBKeyRange.bound([id, -Infinity], [id, Infinity]);
    let cursor = await entries.index('playlist_position').openKeyCursor(range);
    while (cursor) {
      await entries.delete(cursor.primaryKey);
      cursor = await cursor.continue();
    }
    await tx.objectStore('playlist').delete(id);
    await tx.done;
    notifyStores('playlist', 'playlist_entry');
  },
  /** The playlist's entries in order. */
  async entries(playlistId: string): Promise<PlaylistEntryRow[]> {
    const db = await openAudenDb();
    const range = IDBKeyRange.bound([playlistId, -Infinity], [playlistId, Infinity]);
    return db.getAllFromIndex('playlist_entry', 'playlist_position', range);
  },
  /** Appends a track at the end; returns the entry. */
  async addTrack(playlistId: string, trackId: number): Promise<PlaylistEntryRow> {
    const db = await openAudenDb();
    const tx = db.transaction(['playlist', 'playlist_entry'], 'readwrite');
    const range = IDBKeyRange.bound([playlistId, -Infinity], [playlistId, Infinity]);
    const last = await tx.objectStore('playlist_entry').index('playlist_position').openKeyCursor(range, 'prev');
    const position = last ? (last.key as [string, number])[1] + 1 : 0;
    const row: PlaylistEntryRow = { playlistId, trackId, position };
    const entryId = await tx.objectStore('playlist_entry').add(row);
    const playlist = await tx.objectStore('playlist').get(playlistId);
    if (playlist) await tx.objectStore('playlist').put({ ...playlist, updatedAt: Date.now() });
    await tx.done;
    notifyStores('playlist', 'playlist_entry');
    return { ...row, entryId };
  },
  async removeEntry(entryId: number): Promise<void> {
    await (await openAudenDb()).delete('playlist_entry', entryId);
    notifyStores('playlist_entry');
  },
};

export const lyrics = {
  async variants(songKey: string): Promise<LyricsVariantRow[]> {
    const range = IDBKeyRange.bound([songKey, ''], [songKey, '￿']);
    return (await openAudenDb()).getAll('lyrics_variant', range);
  },
  async variant(songKey: string, provider: string): Promise<LyricsVariantRow | undefined> {
    return (await openAudenDb()).get('lyrics_variant', [songKey, provider]);
  },
  async putVariant(row: LyricsVariantRow): Promise<void> {
    await (await openAudenDb()).put('lyrics_variant', row);
    notifyStores('lyrics_variant');
  },
  async removeVariant(songKey: string, provider: string): Promise<void> {
    await (await openAudenDb()).delete('lyrics_variant', [songKey, provider]);
    notifyStores('lyrics_variant');
  },
  async pref(songKey: string): Promise<SongLyricsPrefRow | undefined> {
    return (await openAudenDb()).get('song_lyrics_pref', songKey);
  },
  async putPref(row: SongLyricsPrefRow): Promise<void> {
    await (await openAudenDb()).put('song_lyrics_pref', row);
    notifyStores('song_lyrics_pref');
  },
};

export const spotifyMatches = {
  async get(spotifyId: string): Promise<SpotifyMatchRow | undefined> {
    return (await openAudenDb()).get('spotify_match', spotifyId);
  },
  async all(): Promise<SpotifyMatchRow[]> {
    return (await openAudenDb()).getAll('spotify_match');
  },
  async put(row: SpotifyMatchRow): Promise<void> {
    await (await openAudenDb()).put('spotify_match', row);
    notifyStores('spotify_match');
  },
  async remove(spotifyId: string): Promise<void> {
    await (await openAudenDb()).delete('spotify_match', spotifyId);
    notifyStores('spotify_match');
  },
};
