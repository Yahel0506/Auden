// Port of dev/auden/dj/AndroidPhrases.kt: the DJ's lines by name, in the language the DJ speaks.
// The Android app reads its lines from resources; the web app reads them from the JSON tables beside this file.

import en from './phrases.en.json';
import es from './phrases.es.json';

/** Where the DJ's wording comes from: lists of templates by name, in the listener's language. */
export interface DjPhrases {
  /** The templates for `key`, or empty if there are none in this language. */
  templates(key: string): string[];
}

/** A phrase table from a plain record of key to templates. Keys are matched exactly, never through the prototype. */
export function tableFrom(record: Readonly<Record<string, readonly string[]>>): DjPhrases {
  const table = new Map<string, readonly string[]>(Object.entries(record));
  return {
    templates: (key) => [...(table.get(key) ?? [])],
  };
}

const tables = {
  en: tableFrom(en),
  es: tableFrom(es),
} as const;

/** The DJ's lines in [lang]. Only languages with their own translation are offered, as on Android. */
export function phrasesFor(lang: 'es' | 'en'): DjPhrases {
  return lang === 'es' ? tables.es : tables.en;
}
