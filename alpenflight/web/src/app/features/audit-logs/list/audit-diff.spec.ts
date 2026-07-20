import { describe, expect, it } from 'vitest';

import { buildAuditDiff } from './audit-diff';

describe('buildAuditDiff', () => {
  it('diffs before+after, marking only changed keys', () => {
    const diff = buildAuditDiff({
      beforeState: { callSign: 'HB-1234', seats: 2 },
      afterState: { callSign: 'HB-9999', seats: 2 },
    });
    expect(diff.mode).toBe('diff');
    expect(diff.rows).toEqual([
      { key: 'callSign', before: 'HB-1234', after: 'HB-9999', changed: true },
      { key: 'seats', before: '2', after: '2', changed: false },
    ]);
  });

  it('unions keys added or removed across states and marks them changed', () => {
    const diff = buildAuditDiff({
      beforeState: { removed: 'x' },
      afterState: { added: 'y' },
    });
    expect(diff.mode).toBe('diff');
    expect(diff.rows).toEqual([
      { key: 'removed', before: 'x', after: undefined, changed: true },
      { key: 'added', before: undefined, after: 'y', changed: true },
    ]);
  });

  it('renders after-only for a create (before empty/absent)', () => {
    const diff = buildAuditDiff({ afterState: { name: 'Birrfeld' } });
    expect(diff.mode).toBe('create');
    expect(diff.rows).toEqual([{ key: 'name', after: 'Birrfeld', changed: false }]);
  });

  it('renders before-only for a delete (after empty/absent)', () => {
    const diff = buildAuditDiff({ beforeState: { code: 'SCHOOL' } });
    expect(diff.mode).toBe('delete');
    expect(diff.rows).toEqual([{ key: 'code', before: 'SCHOOL', changed: false }]);
  });

  it('treats an empty-object state as absent', () => {
    expect(buildAuditDiff({ beforeState: {}, afterState: { a: 1 } }).mode).toBe('create');
    expect(buildAuditDiff({ beforeState: {}, afterState: {} }).mode).toBe('empty');
  });

  it('stringifies nested objects and scalars readably', () => {
    const diff = buildAuditDiff({
      afterState: { nested: { a: 1 }, flag: false, count: 0, nil: null },
    });
    expect(diff.rows).toEqual([
      { key: 'nested', after: '{"a":1}', changed: false },
      { key: 'flag', after: 'false', changed: false },
      { key: 'count', after: '0', changed: false },
      { key: 'nil', after: 'null', changed: false },
    ]);
  });
});
