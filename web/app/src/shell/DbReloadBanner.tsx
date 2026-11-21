import { t as translateUi } from '@/i18n';
import { useEffect, useState } from 'react';
import { onDbBlocked } from '@/auden/db';

/** Another tab upgraded the library database: this one can no longer use it until it reloads. */
export function DbReloadBanner() {
  const [stale, setStale] = useState(false);
  useEffect(() => onDbBlocked(() => setStale(true)), []);
  if (!stale) return null;
  return (
    <div role="alert" className="fixed inset-x-0 top-0 z-50 flex items-center justify-center gap-3 bg-key-bg px-4 py-2 type-callout text-white">
      <span>{translateUi("db_reload_banner.auden_was_updated_in_another")}</span>
      <button className="type-callout-emphasized underline" onClick={() => location.reload()}>{translateUi("db_reload_banner.reload")}</button>
    </div>
  );
}
