import type { DeliveryItemDetails } from '@api/generated/model';

/** The DeliveryItem fields the harness diff compares cell-by-cell. */
export const DIFF_FIELDS = [
  'articleNumber',
  'quantity',
  'unitType',
  'itemText',
  'discountInPercent',
] as const satisfies readonly (keyof DeliveryItemDetails)[];

export type DiffField = (typeof DIFF_FIELDS)[number];

/** The transloco label key for each diffable field (scoped under the harness). */
const FIELD_LABEL_KEYS: Record<DiffField, string> = {
  articleNumber: 'edit.diff.fields.articleNumber',
  quantity: 'edit.diff.fields.quantity',
  unitType: 'edit.diff.fields.unitType',
  itemText: 'edit.diff.fields.itemText',
  discountInPercent: 'edit.diff.fields.discountInPercent',
};

/** One field that differs between the expected and created item at a position. */
export interface DiffCell {
  readonly field: DiffField;
  readonly labelKey: string;
  readonly expected: string;
  readonly created: string;
}

/** One position whose expected and created items diverge in ≥1 field. */
export interface DiffRow {
  readonly position: number;
  readonly cells: readonly DiffCell[];
}

function cellText(value: DeliveryItemDetails[DiffField] | undefined): string {
  return value === undefined || value === null ? '' : String(value);
}

/**
 * Pair expected ↔ created by position and surface only the rows (and within them
 * the fields) that diverge. A position present on one side only counts as a full
 * mismatch on every field — so an extra / missing engine line is visible too.
 */
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
