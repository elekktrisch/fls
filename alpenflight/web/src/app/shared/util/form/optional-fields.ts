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
