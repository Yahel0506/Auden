import { describe, expect, it } from 'vitest';
import { extractYoutubeId } from './youtubeIdInName';

// YoutubeIdInNameTest.kt, case for case.
describe('extractYoutubeId', () => {
  it('reads a trailing bracketed id', () => {
    expect(extractYoutubeId('Title [dQw4w9WgXcQ].m4a')).toBe('dQw4w9WgXcQ');
    expect(extractYoutubeId('Artist - Title [-tJYN-eG1zk].opus')).toBe('-tJYN-eG1zk');
  });

  it('reads a url form', () => {
    expect(extractYoutubeId('clip v=dQw4w9WgXcQ.mp3')).toBe('dQw4w9WgXcQ');
  });

  it('reads a youtu.be url form', () => {
    expect(extractYoutubeId('clip youtu.be/dQw4w9WgXcQ.mp3')).toBe('dQw4w9WgXcQ');
  });

  it('ignores what is not an id', () => {
    for (const name of [
      'Title [dQw4w9WgXc].m4a', 'Title [dQw4w9WgXcQQ].m4a', 'Title [dQw4w9WgXcQ] (Live).m4a',
      'Title (dQw4w9WgXcQ).m4a', 'dQw4w9WgXcQ.m4a', 'Title [dQw4w9WgXcR].m4a',
    ]) {
      expect(extractYoutubeId(name), name).toBeNull();
    }
  });

  it('ignores words and counters', () => {
    for (const id of [
      'Butterflies', 'Bonus_Track', 'Hidden_Song', 'theme_music', 'Love_Me_Now', 'TITLE_THEME',
      'Track_01_04', 'REC00010004', 'VID20240104', 'My_Song_004', '07_Sunrises', '12345678904',
    ]) {
      expect(extractYoutubeId(`Artist - Title [${id}].mp4`), id).toBeNull();
    }
  });
});
