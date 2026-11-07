import { describe, expect, it } from 'vitest';
import { AnalysisQueue } from './analysisQueue';

const item = (videoId: string) => ({ videoId });
const drain = (q: AnalysisQueue<{ videoId: string }>) => {
  const out: string[] = [];
  for (let i = q.take(); i; i = q.take()) out.push(i.videoId);
  return out;
};

describe('AnalysisQueue', () => {
  it('keeps arrival order until someone says who is wanted, an urgent request going first', () => {
    const q = new AnalysisQueue<{ videoId: string }>();
    q.add(item('a'), false);
    q.add(item('b'), false);
    q.add(item('c'), true);
    expect(drain(q)).toEqual(['c', 'a', 'b']);
  });

  it('puts the track playing before the one after it, whatever asked first', () => {
    const q = new AnalysisQueue<{ videoId: string }>();
    q.add(item('next'), true);
    q.add(item('now'), false);
    q.retain(['now', 'next']);
    expect(drain(q)).toEqual(['now', 'next']);
  });

  it('drops the songs that were skipped past, and says which', () => {
    const q = new AnalysisQueue<{ videoId: string }>();
    ['skipped1', 'skipped2', 'now', 'next'].forEach((id) => q.add(item(id), false));
    const dropped = q.retain(['now', 'next']);
    expect(dropped.map((d) => d.videoId)).toEqual(['skipped1', 'skipped2']);
    expect(q.has('skipped1')).toBe(false);
    expect(drain(q)).toEqual(['now', 'next']);
  });

  it('knows what is no longer wanted (for the work already running), and forgets it once re-requested into the list', () => {
    const q = new AnalysisQueue<{ videoId: string }>();
    expect(q.isWanted('x')).toBe(true);
    q.retain(['now']);
    expect(q.isWanted('x')).toBe(false);
    expect(q.isWanted('now')).toBe(true);
    q.retain(['x', 'now']);
    expect(q.isWanted('x')).toBe(true);
  });

  it('lets a dropped song come back when it is asked for again', () => {
    const q = new AnalysisQueue<{ videoId: string }>();
    q.add(item('a'), false);
    q.retain(['b']);
    q.add(item('a'), true);
    expect(q.has('a')).toBe(true);
  });

  it('a song asked for after the last retain waits behind the named ones', () => {
    const q = new AnalysisQueue<{ videoId: string }>();
    q.retain(['now', 'next']);
    q.add(item('stranger'), true);
    q.add(item('next'), false);
    q.add(item('now'), false);
    expect(drain(q)).toEqual(['now', 'next', 'stranger']);
  });

  it('does not queue a song twice', () => {
    const q = new AnalysisQueue<{ videoId: string }>();
    q.add(item('a'), false);
    q.add(item('a'), false);
    expect(q.length).toBe(1);
  });
});
