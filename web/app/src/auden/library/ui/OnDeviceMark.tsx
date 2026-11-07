import { HardDrive } from 'lucide-react';
import type { Track } from '@/data/music/types';
import { sourceOf } from '@/playback/source';
import { isOnDevice, useLinkVersion } from '../linkIndex';

/** "On this device": a streamed song that also has a file in the user's folders. Local songs are all on the device, so they carry no mark. */
export function OnDeviceMark({ track }: { track: Track }) {
  useLinkVersion((s) => s.v);
  if (sourceOf(track) !== 'youtube' || !isOnDevice(track)) return null;
  return (
    <span className="ms-1.5 inline-grid shrink-0 place-items-center text-secondary" title="On this device" aria-label="On this device">
      <HardDrive size={12} />
    </span>
  );
}
