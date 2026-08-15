import type { AuditEventRow } from '@api/generated/model';

export type AuditDiffMode = 'diff' | 'create' | 'delete' | 'empty';

export interface AuditDiffRow {
  readonly key: string;
  readonly before?: string | undefined;
  readonly after?: string | undefined;
  readonly changed: boolean;
}

export interface AuditDiff {
  readonly mode: AuditDiffMode;
  readonly rows: readonly AuditDiffRow[];
}

type StateMap = Record<string, unknown> | undefined;

function hasEntries(state: StateMap): boolean {
  return !!state && Object.keys(state).length > 0;
}

function render(value: unknown): string | undefined {
  if (value === undefined) return undefined;
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

export function buildAuditDiff(row: Pick<AuditEventRow, 'beforeState' | 'afterState'>): AuditDiff {
  const before = row.beforeState as StateMap;
  const after = row.afterState as StateMap;
  const hasBefore = hasEntries(before);
  const hasAfter = hasEntries(after);

  if (!hasBefore && !hasAfter) return { mode: 'empty', rows: [] };

  if (hasBefore && !hasAfter) {
    return {
      mode: 'delete',
      rows: Object.keys(before!).map((key) => ({
        key,
        before: render(before![key]),
        changed: false,
      })),
    };
  }

  if (!hasBefore && hasAfter) {
    return {
      mode: 'create',
      rows: Object.keys(after!).map((key) => ({
        key,
        after: render(after![key]),
        changed: false,
      })),
    };
  }

  const keys = Array.from(new Set([...Object.keys(before!), ...Object.keys(after!)]));
  return {
    mode: 'diff',
    rows: keys.map((key) => {
      const b = render(before![key]);
      const a = render(after![key]);
      return { key, before: b, after: a, changed: b !== a };
    }),
  };
}
