import { useQuery } from '@tanstack/react-query';
import { Minus, Plus, X } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { audenKey } from '@/auden/db/queries';
import { useSavedLyricsDialog } from '@/auden/lyrics/dialog';
import { deleteSavedVariant, setOffset, setPerSong, selectSavedVariant, type LyricsOffset } from '@/auden/lyrics/actions';
import { fromStoredProvider, storedProviderLabel, toStoredProvider } from '@/auden/lyrics/names';
import { formatOffset } from '@/auden/lyrics/offset';
import { listVariants, readPref, saveVariant } from '@/auden/lyrics/store';
import { syncKind, variantToResult } from '@/auden/lyrics/variant';
import { lookupLyrics, type LyricsTrack } from '@/lyrics/repository';
import { enabledSources, OFFSET_STEP_MS } from '@/lyrics/settings';
import { sourceById, type LyricsSourceId } from '@/lyrics/sources';

/** The two small dialogs the lyrics panel opens: the song's saved lyrics, and its timing. */

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return createPortal(
    <div className="fixed inset-0 z-(--z-modal) grid animate-fade place-items-center bg-scrim" onPointerDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        className="flex max-h-[min(640px,calc(100vh-48px))] w-[min(460px,calc(100vw-32px))] animate-zoom flex-col rounded-[14px] bg-page shadow-glass"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <header className="flex items-center justify-between px-5 pt-[18px] pb-2">
          <h2 className="type-title-2-emphasized">{title}</h2>
          <button className="grid size-[30px] place-items-center rounded-full bg-[#333336] text-secondary" aria-label="Close" onClick={onClose}>
            <X size={16} />
          </button>
        </header>
        <div className="overflow-y-auto px-5 pb-5">{children}</div>
      </div>
    </div>,
    document.body,
  );
}

const small = 'rounded-[8px] px-2.5 py-1 type-callout-emphasized text-key enabled:hover:bg-quinary disabled:opacity-40';

/** What is saved for the song, with Use / Preview / Delete, and a way to fetch another provider's version. */
export function SavedLyricsDialog({ songKey, track, onClose }: { songKey: string; track: LyricsTrack; onClose: () => void }) {
  const variants = useQuery({ queryKey: audenKey('lyrics_variant', songKey), queryFn: () => listVariants(songKey) });
  const pref = useQuery({ queryKey: audenKey('song_lyrics_pref', songKey), queryFn: () => readPref(songKey) });
  const [preview, setPreview] = useState<string | null>(null);
  const [fetching, setFetching] = useState<LyricsSourceId | null>(null);
  const [message, setMessage] = useState('');
  const saved = new Set((variants.data ?? []).map((v) => v.provider));
  const active = pref.data?.activeProvider ?? null;

  const fetchFrom = async (id: LyricsSourceId) => {
    setFetching(id);
    setMessage('');
    try {
      const found = await lookupLyrics(track, { only: id, fresh: true });
      if (found) await saveVariant(songKey, found, { makeActive: false });
      else setMessage(`${sourceById(id)?.label ?? id} hasn’t got this song.`);
    } finally {
      setFetching(null);
    }
  };
  const others = enabledSources().filter((id) => !saved.has(storedName(id)));

  return (
    <Modal title="Saved lyrics" onClose={onClose}>
      {!variants.data?.length ? (
        <p className="py-3 type-callout text-secondary">Nothing is saved for this song. “Save these lyrics” in the provider list keeps what is on screen, so it opens offline and stays when you switch.</p>
      ) : (
        <ul className="overflow-hidden rounded-[10px] bg-quinary">
          {variants.data.map((v) => {
            const result = variantToResult(v);
            const id = fromStoredProvider(v.provider);
            const isActive = v.provider === active;
            return (
              <li key={v.provider} className="px-3 py-2 [&+&]:border-t-[0.5px] [&+&]:border-t-divider">
                <div className="flex items-center gap-2">
                  <span className="min-w-0 flex-1">
                    <span className="type-body-emphasized">{storedProviderLabel(v.provider)}</span>
                    <span className="block type-callout text-secondary">
                      {result ? `${syncKind(result)}-timed · ${result.lines.length} lines` : 'Can’t be read'} · {new Date(v.fetchedAt).toLocaleDateString()}
                      {isActive ? ' · in use' : ''}
                    </span>
                  </span>
                  <button className={small} disabled={!id || isActive} onClick={() => id && void selectSavedVariant(track.videoId, id)}>
                    Use
                  </button>
                  <button className={small} disabled={!result} onClick={() => setPreview(preview === v.provider ? null : v.provider)}>
                    Preview
                  </button>
                  <button className={small} onClick={() => void deleteSavedVariant(track.videoId, v.provider)}>
                    Delete
                  </button>
                </div>
                {preview === v.provider && result && (
                  <p className="mt-1.5 max-h-40 overflow-y-auto whitespace-pre-line type-callout text-secondary">
                    {result.lines.slice(0, 14).map((l) => l.text).join('\n')}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {others.length > 0 && (
        <>
          <h3 className="mx-1 mt-[18px] mb-1.5 type-subhead-emphasized uppercase text-secondary">Fetch another provider’s</h3>
          <ul className="overflow-hidden rounded-[10px] bg-quinary">
            {others.map((id) => (
              <li key={id} className="flex items-center gap-2 py-1.5 pr-2.5 pl-3 [&+&]:border-t-[0.5px] [&+&]:border-t-divider">
                <span className="min-w-0 flex-1 truncate type-body">{sourceById(id)?.label}</span>
                <button className={small} disabled={fetching !== null} onClick={() => void fetchFrom(id)}>
                  {fetching === id ? 'Fetching…' : 'Fetch'}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {message && <p className="mx-1 mt-2 type-callout text-secondary">{message}</p>}
    </Modal>
  );
}

const storedName = (id: LyricsSourceId) => toStoredProvider(id);

/** The lyrics' timing: this song's, or every song's, in steps of a tenth of a second. */
export function OffsetSheet({ songKey, offset, onClose }: { songKey: string | null; offset: LyricsOffset; onClose: () => void }) {
  const editing = offset.perSong ? (offset.own ?? 0) : offset.global;
  const step = (by: number) => void setOffset(songKey, offset.perSong, editing + by);
  const mode = 'flex-1 rounded-[8px] py-1.5 type-callout-emphasized';
  return (
    <Modal title="Lyrics timing" onClose={onClose}>
      <p className="mb-3 type-callout text-secondary">Positive shows the lyrics later, negative sooner.</p>
      <div className="mb-4 flex gap-1 rounded-[10px] bg-quinary p-1" role="tablist">
        <button
          role="tab"
          aria-selected={offset.perSong}
          disabled={!songKey}
          className={`${mode} ${offset.perSong ? 'bg-page text-primary' : 'text-secondary'}`}
          onClick={() => songKey && void setPerSong(songKey, true, offset.effective)}
        >
          This song
        </button>
        <button
          role="tab"
          aria-selected={!offset.perSong}
          className={`${mode} ${!offset.perSong ? 'bg-page text-primary' : 'text-secondary'}`}
          onClick={() => songKey && void setPerSong(songKey, false, 0)}
        >
          All songs
        </button>
      </div>
      <div className="flex items-center justify-center gap-5 py-2">
        <button className="grid size-9 place-items-center rounded-full bg-quinary" aria-label="Earlier by 0.1 s" onClick={() => step(-OFFSET_STEP_MS)}>
          <Minus size={16} />
        </button>
        <span className="min-w-24 text-center type-title-2-emphasized tabular-nums" aria-live="polite">
          {formatOffset(editing)}
        </span>
        <button className="grid size-9 place-items-center rounded-full bg-quinary" aria-label="Later by 0.1 s" onClick={() => step(OFFSET_STEP_MS)}>
          <Plus size={16} />
        </button>
      </div>
      <button className="mt-2 type-callout-emphasized text-key" onClick={() => void setOffset(songKey, offset.perSong, 0)}>
        Reset
      </button>
    </Modal>
  );
}

/** The one saved-lyrics dialog, opened from any song menu; mounted once in the shell. */
export function SavedLyricsHost() {
  const open = useSavedLyricsDialog((s) => s.open);
  const hide = useSavedLyricsDialog((s) => s.hide);
  return open ? <SavedLyricsDialog songKey={open.songKey} track={open.track} onClose={hide} /> : null;
}
