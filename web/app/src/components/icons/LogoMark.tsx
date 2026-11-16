import markSvg from '@/assets/logo-mark.svg?raw';

// Auden's mark — a music note that is also an A — filled with
// currentColor so it follows the text colour it sits in.
const markup = markSvg.replace(/width="\d+" height="\d+"/, 'width="100%" height="100%"');

export function LogoMark({ size = 20, className }: { size?: number; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={className}
      // `size` is the mark's height; it is taller than it is wide.
      style={{ display: 'inline-flex', width: (size * 1261) / 1435, height: size }}
      dangerouslySetInnerHTML={{ __html: markup }}
    />
  );
}
