import { queryClient } from '@/lib/queryClient';
import { extensionQueryKey } from '@/extension/useExtension';

/** Whether the extension is there to stream a YouTube song right now (outside React; the player's own gate reads the same cache). */
export function youtubeStreamable(): boolean {
  const ext = queryClient.getQueryData(extensionQueryKey) as { info?: { capabilities?: string[] } } | null | undefined;
  return !!ext?.info?.capabilities?.includes('yt');
}
