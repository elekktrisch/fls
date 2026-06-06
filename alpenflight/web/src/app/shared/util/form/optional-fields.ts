/**
 * Build a request body from a base + an optionals map, dropping any optional
 * that is empty (`''`, `null`, or `undefined`). Replaces the long, duplicated
 * `if (v.x !== '') req.x = …` chains in the per-feature `formToCreateRequest` /
 * `formToUpdateRequest` builders (the `*-edit.page.ts` form-mapping hotspot —
 * see `_BOYSCOUT.md`). One pass, no per-field branch — keeps the new edit
 * page's cyclomatic/CRAP low.
 *
 * An empty-string optional is treated as "not provided" (the convention the
 * legacy forms used: a blank text field omits the wire field rather than
 * sending `""`).
 */
export function withOptionals<TBase extends object, TOpt extends object>(
  base: TBase,
  optionals: TOpt,
): TBase & Partial<TOpt> {
  const out: Record<string, unknown> = { ...(base as Record<string, unknown>) };
  for (const [k, v] of Object.entries(optionals)) {
    if (v !== '' && v !== null && v !== undefined) {
      out[k] = v;
    }
  }
  return out as TBase & Partial<TOpt>;
}
