import englishMessages from './i18n.en.json';

const translatedCatalogs = import.meta.glob<{ default: Record<string, string> }>('./i18n.*.json');

export type UiMessageKey = keyof typeof englishMessages;
export type UiMessageValues = Record<string, string | number>;

const supportedLocales = [
  'en',
  'es',
  'fr',
  'de',
  'pl',
  'pt',
  'id',
  'hi',
  'ja',
  'ru',
  'zh-CN',
  'zh-TW',
  'he',
  'it',
  'tr',
  'th',
  'vi',
  'ko',
] as const;

function browserLanguages(): string[] {
  if (typeof navigator === 'undefined') return ['en'];
  return navigator.languages?.length ? [...navigator.languages] : [navigator.language || 'en'];
}

export function resolveUiLocale(languages = browserLanguages()): string {
  for (const language of languages) {
    let locale: string;
    try {
      locale = Intl.getCanonicalLocales(language)[0] ?? '';
    } catch {
      continue;
    }

    const parts = new Intl.Locale(locale);
    if (parts.language === 'zh') {
      if (parts.script === 'Hant') return 'zh-TW';
      if (parts.script === 'Hans') return 'zh-CN';
      return parts.region === 'TW' || parts.region === 'HK' || parts.region === 'MO' ? 'zh-TW' : 'zh-CN';
    }

    if (supportedLocales.includes(parts.language as (typeof supportedLocales)[number])) {
      return parts.language;
    }
  }
  return 'en';
}

export const uiLocale = resolveUiLocale();
export const uiDirection = uiLocale === 'he' ? 'rtl' : 'ltr';
let messages: Partial<Record<UiMessageKey, string>> = {};

export async function loadUiMessages(): Promise<void> {
  if (uiLocale === 'en') return;
  const loadCatalog = translatedCatalogs[`./i18n.${uiLocale}.json`];
  if (!loadCatalog) return;
  try {
    messages = await loadCatalog().then((module) => module.default);
  } catch (error) {
    console.error(`Auden: could not load the ${uiLocale} UI catalog`, error);
  }
}

export function applyUiLocale(documentElement: HTMLElement = document.documentElement): void {
  documentElement.lang = uiLocale;
  documentElement.dir = uiDirection;
}

export function t(key: UiMessageKey, values?: UiMessageValues): string {
  let source: string = messages[key] ?? englishMessages[key];
  if (!values) return source;
  // Support ICU-style named plural blocks, e.g. {count, plural, one {# song} other {# songs}}.
  // Parse balanced braces so complete sentences and nested placeholders remain intact.
  let cursor = 0;
  while (cursor < source.length) {
    const open = source.indexOf('{', cursor);
    if (open < 0) break;
    const header = /^\{([A-Za-z][\w]*),\s*plural,\s*/.exec(source.slice(open));
    if (!header) { cursor = open + 1; continue; }
    const bodyStart = open + header[0].length;
    let depth = 1;
    let end = bodyStart;
    for (; end < source.length && depth; end++) {
      if (source[end] === '{') depth++;
      else if (source[end] === '}') depth--;
    }
    if (depth !== 0) { cursor = bodyStart; continue; }
    const value = values[header[1]!];
    if (typeof value !== 'number') { cursor = end; continue; }
    const body = source.slice(bodyStart, end - 1);
    const forms = new Map<string, string>();
    const formPattern = /(=?\w+)\s*\{/g;
    let formMatch: RegExpExecArray | null;
    while ((formMatch = formPattern.exec(body))) {
      let formDepth = 1;
      let formEnd = formPattern.lastIndex;
      for (; formEnd < body.length && formDepth; formEnd++) {
        if (body[formEnd] === '{') formDepth++;
        else if (body[formEnd] === '}') formDepth--;
      }
      if (formDepth === 0) forms.set(formMatch[1]!, body.slice(formPattern.lastIndex, formEnd - 1));
      formPattern.lastIndex = formEnd;
    }
    const selected = forms.get(`=${value}`) ?? forms.get(new Intl.PluralRules(uiLocale).select(value)) ?? forms.get('other');
    if (selected !== undefined) {
      const formatted = selected.replace(/#/g, new Intl.NumberFormat(uiLocale).format(value));
      source = source.slice(0, open) + formatted + source.slice(end);
      cursor = open + formatted.length;
    } else cursor = end;
  }
  return source.replace(/\{([A-Za-z][\w]*)\}/g, (placeholder, name: string) =>
    Object.hasOwn(values, name) ? String(values[name]) : placeholder,
  );
}

export function plural(
  value: number,
  forms: Partial<Record<Intl.LDMLPluralRule, string>> & { other: string },
  locale = uiLocale,
): string {
  const category = new Intl.PluralRules(locale).select(value);
  return forms[category] ?? forms.other;
}

export function number(value: number, options?: Intl.NumberFormatOptions, locale = uiLocale): string {
  return new Intl.NumberFormat(locale, options).format(value);
}

export function date(value: Date | number, options?: Intl.DateTimeFormatOptions, locale = uiLocale): string {
  return new Intl.DateTimeFormat(locale, options).format(value);
}
