import { describe, expect, it } from 'vitest';

import type { DeliveryItemDetails } from '@api/generated/model';

import { deliveryItemDiff } from './delivery-item-diff';

const tier = (articleNumber: string, quantity: number): DeliveryItemDetails => ({
  articleNumber,
  itemText: articleNumber,
  quantity,
  unitType: 'Sec',
});

describe('deliveryItemDiff', () => {
  it('returns no rows when expected and created match field-for-field', () => {
    const items = [tier('A-FT-1', 1800), tier('A-FT-2', 1800)];
    expect(deliveryItemDiff(items, [...items.map((i) => ({ ...i }))])).toEqual([]);
  });

  it('surfaces a differing field by position with both values', () => {
    const expected = [tier('A-FT-1', 1800), tier('A-FT-2', 1800), tier('A-FT-3', 600)];
    const created = [tier('A-FT-1', 1800), tier('A-FT-2', 1500), tier('A-FT-3', 600)];

    const rows = deliveryItemDiff(expected, created);

    expect(rows).toHaveLength(1);
    expect(rows[0]!.position).toBe(1);
    expect(rows[0]!.cells).toEqual([
      {
        field: 'quantity',
        labelKey: 'edit.diff.fields.quantity',
        expected: '1800',
        created: '1500',
      },
    ]);
  });

  it('flags every field of a position present on only one side', () => {
    const expected = [tier('A-FT-1', 1800)];
    const created = [tier('A-FT-1', 1800), tier('A-FT-2', 600)];

    const rows = deliveryItemDiff(expected, created);

    expect(rows).toHaveLength(1);
    expect(rows[0]!.position).toBe(1);
    expect(rows[0]!.cells.map((c) => c.field)).toEqual([
      'articleNumber',
      'quantity',
      'unitType',
      'itemText',
    ]);
    expect(rows[0]!.cells[0]).toEqual({
      field: 'articleNumber',
      labelKey: 'edit.diff.fields.articleNumber',
      expected: '',
      created: 'A-FT-2',
    });
  });
});
