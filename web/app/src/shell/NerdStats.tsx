import { number, t as translateUi } from '@/i18n';
import { useEffect, useReducer, useState } from 'react';
import { useParty } from '@/party/listenTogether';
import type { AutoDJSnapshot } from '@/playback/automix';
import type { analyzer as Analyzer } from '@/playback/automix/analysis/analyzer';
import type { NowPlaying } from '@/state/player';
import { usePlayer } from '@/state/player';

/**
 * Stats for nerds, under the artist in the full-screen player (the phone's
 * SleeveNerdStats): the stream as it plays, and under it, dimmer, what
 * Automix is doing. Nothing at all while the setting is off.
 */
export function NerdStats() {
  const show = usePlayer((s) => s.showNerdStats);
  const nowPlaying = usePlayer((s) => s.nowPlaying);
  const automix = usePlayer((s) => s.automix);
  if (!show || !nowPlaying) return null;
  return (
    // The phone's shadow: free on the dark mesh, and what keeps it legible over a bright music video.
    <div className="mt-1 flex min-w-0 flex-col gap-px type-[500_11px/14px] tabular-nums [text-shadow:0_1px_4px_rgb(0_0_0/0.55)]">
      <span className="truncate text-secondary">{describeStream(nowPlaying) ?? translateUi("shell_nerdstats_measuring_the_stream")}</span>
      {automix && <AutomixLine videoId={nowPlaying.videoId} />}
    </div>
  );
}

/** "Opus · 48 kHz · 141 kbps · Stereo · itag 251 · VISIONOS direct", as much as is known. */
function describeStream(np: NowPlaying): string | null {
  const s = np.stream;
  if (!s) return null;
  const parts = [
    s.codec,
    s.sampleRateHz ? `${number(s.sampleRateHz / 1000, { maximumFractionDigits: 1 })} kHz` : null,
    s.bitrateKbps ? `${number(s.bitrateKbps)} kbps` : null,
    s.channels ? s.channels === 1 ? translateUi('nerd_stats.mono') : s.channels === 2 ? translateUi('nerd_stats.stereo') : translateUi('nerd_stats.channels', { count: s.channels }) : null,
    s.itag ? `itag ${s.itag}` : null,
    `${s.client} ${translateUi(({ direct: 'nerd_stats.mode_direct', SABR: 'nerd_stats.mode_sabr', file: 'nerd_stats.mode_file' } as const)[s.mode])}`,
  ];
  return parts.filter(Boolean).join(' · ');
}

/** Automix's line: both songs' analysis while it waits, the blend once one is planned. Both sides always named. */
function AutomixLine({ videoId }: { videoId: string }) {
  const state = usePlayer((s) => s.automixState);
  const nextInQueue = usePlayer((s) => s.queue[s.index + 1]?.track.videoId ?? null);
  const inParty = useParty((s) => !!s.code);
  const analyzer = useAnalyzer();

  let text: string;
  if (inParty) text = translateUi('nerd_stats.stopped_in_party');
  else if (state?.state === 'scheduled' && state.plan) text = describeBlend(state);
  else if (state?.loop) text = translateUi('nerd_stats.loop_mix_ready', { loop: describeLoop(state) });
  else {
    // This song is read where it will be mixed out (its ending's grid), the next where it comes in (its opening's).
    const label = (id: string | null, outgoing: boolean) => {
      if (!id || !analyzer) return translateUi('nerd_stats.waiting');
      const a = analyzer.get(id);
      if (a) {
        const g = outgoing && a.tailGrid !== undefined ? a.tailGrid : a.bpm ? { bpm: a.bpm, gridConfidence: a.gridConfidence } : null;
        const key = a.camelot ? `${a.camelot.num}${a.camelot.letter}` : '';
        const video = a.musicVideo ? translateUi('nerd_stats.music_video_no_loop_suffix') : '';
        if (!g) return translateUi('nerd_stats.analysis_without_beat', { key: key ? ` ${key}` : '', video });
        const grid = translateUi(g.gridConfidence >= 0.2 ? 'nerd_stats.grid_confidence' : 'nerd_stats.weak_grid_confidence', { confidence: number(g.gridConfidence, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) });
        return translateUi('nerd_stats.analysis_with_grid', { bpm: number(Math.round(g.bpm)), key: key ? ` ${key}` : '', grid, video });
      }
      const status = analyzer.statusOf(id);
      return status === 'analyzing' ? translateUi('nerd_stats.analysing') : status === 'error' ? translateUi('nerd_stats.failed') : translateUi('nerd_stats.waiting');
    };
    const next = state?.nextKey ?? nextInQueue;
    text = translateUi('nerd_stats.this_song_next', { thisSong: label(videoId, true), next: next ? label(next, false) : translateUi('nerd_stats.none') });
  }
  // Dimmer than the stream line: that one describes the audio, this one the app.
  return <span className="truncate text-tertiary">{text}</span>;
}

/** "out 4-bar loop, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars". */
function describeLoop(state: AutoDJSnapshot): string {
  const l = state.loop!;
  const from = number(Math.round(l.out.bpm));
  const to = number(Math.round(l.in.bpm / l.fold));
  const tempo = from === to ? translateUi('nerd_stats.same_tempo', { bpm: from }) : translateUi('nerd_stats.ramped_tempo', { from, to, bars: translateUi('nerd_stats.bar_count', { count: l.rampBars }) });
  const fold = l.fold === 1 ? '' : l.fold > 1 ? ` ${translateUi('nerd_stats.next_double_time')}` : ` ${translateUi('nerd_stats.next_half_time')}`;
  const side = (p: typeof l.out) => translateUi('nerd_stats.loop_side', { bars: translateUi('nerd_stats.bar_count', { count: p.bars }), clearBy: translateUi(({ lyrics: 'nerd_stats.lyrics', mask: 'nerd_stats.mask', 'lyrics+mask': 'nerd_stats.lyrics_and_mask' } as const)[p.checkedBy]) });
  return translateUi('nerd_stats.loop_description', { out: side(l.out), in: side(l.in), tempo, fold });
}

function describeBlend(state: AutoDJSnapshot): string {
  const plan = state.plan!;
  const when = state.startsInSec !== null && state.startsInSec > 0
    ? translateUi('nerd_stats.in_seconds', { seconds: translateUi('nerd_stats.seconds_count', { count: Math.ceil(state.startsInSec) }) })
    : translateUi('nerd_stats.now');
  if (plan.style === 'loop' && state.loop) {
    return translateUi('nerd_stats.loop_blend', { action: state.startsInSec !== null && state.startsInSec > 0 ? translateUi('nerd_stats.loop_mix_when', { when }) : translateUi('nerd_stats.looping'), loop: describeLoop(state) });
  }
  const reason = plan.reason ? localizedReason(plan.reason) : translateUi('nerd_stats.no_beat_match');
  if (plan.style === 'crossfade') return translateUi('nerd_stats.crossfading', { when, seconds: number(Math.round(plan.durationSec)), reason });
  const bars = state.beatSec ? Math.round(plan.durationSec / (4 * state.beatSec)) : null;
  const duration = bars ? translateUi('nerd_stats.bar_count', { count: bars }) : translateUi('nerd_stats.seconds_decimal', { seconds: number(plan.durationSec, { maximumFractionDigits: 1 }) });
  if (plan.style === 'filter') return translateUi('nerd_stats.filter_mix', { when, duration, reason });
  const stretch = Math.abs(plan.rate - 1) < 0.0005 ? translateUi('nerd_stats.same_tempo_label') : translateUi('nerd_stats.next_at_rate', { rate: number(plan.rate, { minimumFractionDigits: 3, maximumFractionDigits: 3 }) });
  return translateUi('nerd_stats.mixing', { when, duration, stretch });
}

function localizedReason(reason: string): string {
  if (reason === 'this song has no beat grid at its end') return translateUi('nerd_stats.reason_no_outgoing_grid');
  if (reason === 'next song has no beat grid') return translateUi('nerd_stats.reason_no_incoming_grid');
  const tempos = /^tempos (\d+)% apart$/.exec(reason);
  return tempos ? translateUi('nerd_stats.reason_tempos_apart', { percent: number(Number(tempos[1])) }) : reason;
}

/** The analysis queue, once the Automix code is loaded; re-renders as its statuses change. */
function useAnalyzer(): typeof Analyzer | null {
  const [instance, setInstance] = useState<typeof Analyzer | null>(null);
  const [, bump] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    let unsubscribe: (() => void) | undefined;
    let live = true;
    void import('@/playback/automix/analysis/analyzer').then(({ analyzer }) => {
      if (!live) return;
      setInstance(analyzer);
      unsubscribe = analyzer.subscribe(bump);
    });
    return () => {
      live = false;
      unsubscribe?.();
    };
  }, []);
  return instance;
}

/**
 * The stretch of the seek bar the next Automix blend covers (the phone's
 * transitionWindow): brighter than the unplayed track, under the played
 * fill, feathered at both ends because a blend has no hard edge in time.
 * Once the blend runs it steps aside for the fill's own beat pulse
 * (useScrubber). Place it inside the bar's track, before the fill.
 */
export function MixWindowHighlight() {
  const mix = usePlayer((s) => s.automixState?.window ?? null);
  const startsIn = usePlayer((s) => s.automixState?.startsInSec ?? null);
  const videoId = usePlayer((s) => s.nowPlaying?.videoId);
  const duration = usePlayer((s) => s.nowPlaying?.durationSec ?? 0);
  const automix = usePlayer((s) => s.automix);
  // The last window stays drawn while it fades out.
  const [shown, setShown] = useState(mix);
  useEffect(() => {
    if (mix) setShown(mix);
  }, [mix]);
  const visible = automix && !!mix && mix.videoId === videoId && duration > 0;
  const w = shown;
  if (!w || duration <= 0) return null;
  const left = Math.max(0, Math.min(1, w.startSec / duration));
  const right = Math.max(left, Math.min(1, w.endSec / duration));
  // once the blend runs, the bar's own fill covers it and breathes on the beat (useScrubber)
  const blending = startsIn !== null && startsIn <= 0;
  return (
    <div
      className="pointer-events-none absolute inset-y-0 transition-opacity duration-500"
      style={{ left: `${left * 100}%`, width: `${(right - left) * 100}%`, opacity: visible && !blending ? 1 : 0 }}
      aria-hidden="true"
    >
      <div
        className="size-full bg-tertiary"
        style={{
          maskImage: 'linear-gradient(to right, transparent, #000 30%, #000 70%, transparent)',
        }}
      />
    </div>
  );
}
