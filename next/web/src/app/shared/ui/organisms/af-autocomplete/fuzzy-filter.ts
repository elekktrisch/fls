/**
 * Case-insensitive substring match across multiple fields. Returns items
 * whose value at any of `searchFields` contains `query` (case-insensitive).
 */
export function fuzzyFilter<T>(
  items: readonly T[],
  searchFields: readonly (keyof T)[],
  query: string,
): readonly T[] {
  const q = query.trim().toLowerCase();
  if (q === '') return items;
  return items.filter((item) =>
    searchFields.some((field) => {
      const value = item[field];
      return typeof value === 'string' && value.toLowerCase().includes(q);
    }),
  );
}
