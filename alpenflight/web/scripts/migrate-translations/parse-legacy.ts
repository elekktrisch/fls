/**
 * Parses the legacy `FLSTest/3 insert/10 insert internationalisation values.sql`
 * file into structured rows. Format the script expects (one INSERT per
 * row, multi-line):
 *
 *   INSERT INTO [dbo].[LanguageTranslations] (...)
 *        VALUES (NEWID(), 'TRANSLATION_KEY', 'value', LanguageId, SYSDATETIME())
 *
 * Only the `(key, languageId, value)` triple is captured; the other
 * columns are bookkeeping.
 */

export interface LegacyRow {
  readonly key: string;
  readonly languageId: number;
  readonly value: string;
}

// The legacy seed currently maps `LanguageId 1 → DE`. Other IDs aren't
// emitted today; the table is kept so a future operator can extend the
// migration when prod legacy DBs surface en/fr rows. Unknown IDs are
// skipped with a warning rather than failing the parse — that keeps the
// tool useful against partial dumps.
export const LEGACY_LANG_ID_TO_LOCALE: Record<number, string> = {
  1: 'de',
};

const INSERT_PATTERN =
  /VALUES\s*\(\s*NEWID\(\)\s*,\s*'((?:[^']|'')*)'\s*,\s*'((?:[^']|'')*)'\s*,\s*(\d+)\s*,\s*SYSDATETIME\(\)\s*\)/gi;

export function parseLegacySql(sql: string): LegacyRow[] {
  const rows: LegacyRow[] = [];
  INSERT_PATTERN.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = INSERT_PATTERN.exec(sql)) !== null) {
    const [, rawKey, rawValue, rawId] = m;
    const key = rawKey!.replace(/''/g, "'");
    const value = rawValue!.replace(/''/g, "'");
    const languageId = Number.parseInt(rawId!, 10);
    rows.push({ key, languageId, value });
  }
  return rows;
}
