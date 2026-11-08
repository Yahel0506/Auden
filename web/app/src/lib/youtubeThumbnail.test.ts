import { describe, expect, it } from 'vitest';
import { youtubeThumbnail } from './artwork';

describe('youtubeThumbnail', () => {
  it('is the video thumbnail for a YouTube id and nothing for anything else', () => {
    expect(youtubeThumbnail('x6a2KkZlI0c')).toBe('https://i.ytimg.com/vi/x6a2KkZlI0c/hqdefault.jpg');
    expect(youtubeThumbnail('auden:local:abc')).toBeNull();
    expect(youtubeThumbnail('auden:clip:1')).toBeNull();
    expect(youtubeThumbnail('')).toBeNull();
  });
});
