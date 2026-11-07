import { artworkAtPixels } from './artwork';

/**
 * The artwork as a soft, full-bleed picture (the phone's `fullArtworkBlurImage`: a small capture box-blurred
 * a few times). Blurred ONCE, on a small canvas, and shown scaled up: nothing blurs per frame, which a
 * CSS `blur()` over the whole window would, and a 120 Hz display makes that expensive.
 */
export const SIDE = 96;
export const PASSES = 3;
export const RADIUS = 6;

/** One separable box blur pass over RGBA pixels, edges clamped; reads [src], writes [dst] (same size). */
export function boxBlurPass(src: Uint8ClampedArray, dst: Uint8ClampedArray, width: number, height: number, radius: number): void {
  const tmp = new Uint8ClampedArray(src.length);
  const span = radius * 2 + 1;
  // Horizontal: src -> tmp. A running sum, so the cost does not grow with the radius.
  for (let y = 0; y < height; y++) {
    for (let c = 0; c < 4; c++) {
      let sum = 0;
      for (let x = -radius; x <= radius; x++) sum += src[(y * width + Math.min(width - 1, Math.max(0, x))) * 4 + c]!;
      for (let x = 0; x < width; x++) {
        tmp[(y * width + x) * 4 + c] = sum / span;
        const out = Math.max(0, x - radius);
        const into = Math.min(width - 1, x + radius + 1);
        sum += src[(y * width + into) * 4 + c]! - src[(y * width + out) * 4 + c]!;
      }
    }
  }
  // Vertical: tmp -> dst.
  for (let x = 0; x < width; x++) {
    for (let c = 0; c < 4; c++) {
      let sum = 0;
      for (let y = -radius; y <= radius; y++) sum += tmp[(Math.min(height - 1, Math.max(0, y)) * width + x) * 4 + c]!;
      for (let y = 0; y < height; y++) {
        dst[(y * width + x) * 4 + c] = sum / span;
        const out = Math.max(0, y - radius);
        const into = Math.min(height - 1, y + radius + 1);
        sum += tmp[(into * width + x) * 4 + c]! - tmp[(out * width + x) * 4 + c]!;
      }
    }
  }
}

/** [passes] box blurs in a row, which approach a Gaussian. Returns a new array; [data] is left alone. */
export function boxBlur(data: Uint8ClampedArray, width: number, height: number, passes = PASSES, radius = RADIUS): Uint8ClampedArray<ArrayBuffer> {
  let from = new Uint8ClampedArray(new ArrayBuffer(data.length));
  from.set(data);
  let to = new Uint8ClampedArray(new ArrayBuffer(data.length));
  for (let i = 0; i < passes; i++) {
    boxBlurPass(from, to, width, height, radius);
    [from, to] = [to, from];
  }
  return from;
}

const cache = new Map<string, string>();
const inFlight = new Map<string, Promise<string>>();
const CACHE_MAX = 6;

async function make(url: string): Promise<string> {
  const image = new Image();
  image.crossOrigin = 'anonymous';
  image.referrerPolicy = 'no-referrer';
  image.decoding = 'async';
  // 120px: a size the CDN already has for the lists (lib/artwork.ts).
  image.src = artworkAtPixels(url, 120);
  await image.decode();
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = SIDE;
  const context = canvas.getContext('2d', { willReadFrequently: true })!;
  context.drawImage(image, 0, 0, SIDE, SIDE);
  const blurred = boxBlur(context.getImageData(0, 0, SIDE, SIDE).data, SIDE, SIDE);
  context.putImageData(new ImageData(blurred, SIDE, SIDE), 0, 0);
  // PNG, not JPEG: at 96px it is a few KB, and it is stretched ~20x, where JPEG blocks would band.
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
  if (!blob) throw new Error('Could not encode the blurred cover');
  return URL.createObjectURL(blob);
}

/** An object URL of the blurred artwork, remembered for the last few tracks. Rejects if the image cannot be read. */
export function blurredCover(url: string): Promise<string> {
  const known = cache.get(url);
  if (known) {
    // Least recently USED goes first: the picture on screen is the one just asked for.
    cache.delete(url);
    cache.set(url, known);
    return Promise.resolve(known);
  }
  // Two asks for one cover at once (a quick A, B, A; React's double effect in development) share one blob.
  let pending = inFlight.get(url);
  if (!pending) {
    pending = make(url)
      .then((objectUrl) => {
        cache.set(url, objectUrl);
        if (cache.size > CACHE_MAX) {
          const oldest = cache.keys().next().value!;
          const gone = cache.get(oldest)!;
          cache.delete(oldest);
          // Let go a while after it was evicted, so a picture still fading out is not pulled from under it.
          setTimeout(() => URL.revokeObjectURL(gone), 5_000);
        }
        return objectUrl;
      })
      .finally(() => inFlight.delete(url));
    inFlight.set(url, pending);
  }
  return pending;
}
