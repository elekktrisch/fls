import type { DeliveryItemDetails } from '@api/generated/model';

export const DIFF_FIELDS = [
  'articleNumber',
  'quantity',
  'unitType',
  'itemText',
  'discountInPercent',
] as const satisfies readonly (keyof DeliveryItemDetails)[];

export type DiffField = (typeof DIFF_FIELDS)[number];

const FIELD_LABEL_KEYS: Record<DiffField, string> = {
  articleNumber: 'edit.diff.fields.articleNumber',
  quantity: 'edit.diff.fields.quantity',
  unitType: 'edit.diff.fields.unitType',
  itemText: 'edit.diff.fields.itemText',
  discountInPercent: 'edit.diff.fields.discountInPercent',
};

export interface DiffCell {
  readonly field: DiffField;
  readonly labelKey: string;
  readonly expected: string;
  readonly created: string;
}

export interface DiffRow {
  readonly position: number;
  readonly cells: readonly DiffCell[];
}

function cellText(value: DeliveryItemDetails[DiffField] | undefined): string {
  return value === undefined || value === null ? '' : String(value);
}

export function deliveryItemDiff(
  expected: readonly DeliveryItemDetails[],
  created: readonly DeliveryItemDetails[],
): DiffRow[] {
  const rows: DiffRow[] = [];
  const count = Math.max(expected.length, created.length);
  for (let position = 0; position < count; position++) {
    const exp = expected[position];
    const act = created[position];
    const cells: DiffCell[] = [];
    for (const field of DIFF_FIELDS) {
      const expText = cellText(exp?.[field]);
      const actText = cellText(act?.[field]);
      if (expText !== actText) {
        cells.push({
          field,
          labelKey: FIELD_LABEL_KEYS[field],
          expected: expText,
          created: actText,
        });
      }
    }
    if (cells.length > 0) rows.push({ position, cells });
  }
  return rows;
}
